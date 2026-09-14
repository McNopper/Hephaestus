package com.opencode.ide.board.model;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.board.fleet.FleetLauncher;
import com.opencode.ide.fleet.DispatchGuard;
import com.opencode.ide.fleet.FleetControl;
import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.dispatch.DispatchScheduler;
import com.opencode.ide.fleet.dispatch.DispatchScheduler.LaunchAttempt;
import com.opencode.ide.fleet.dispatch.CostOverview;
import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.Task;

/**
 * Background-only Board adapter to the shared fleet scheduler. Each instance
 * pins one root/project/sprint; a toolbar change cannot redirect an admitted id.
 * The private model is never shared with the view's mutable selection model.
 */
public final class BoardDispatch {

    private final BoardModel model;
    private final FleetLauncher launcher;
    private final BooleanSupplier cancelled;

    public BoardDispatch(Path root, String project, String sprint, FleetLauncher launcher,
            BooleanSupplier cancelled) {
        model = new BoardModel(root, project);
        model.setSprint(sprint);
        this.launcher = launcher;
        this.cancelled = cancelled;
    }

    public DispatchScheduler scheduler(Supplier<AutoDispatch> policy) {
        AutoDispatch effective = policy.get();
        return DispatchScheduler.withFeedback(effective, model::sprintTasks, model::projectTasks,
                model::costOverview, () -> DispatchGuard.runningIds(FleetControl.repoRootOf(model.root())),
                (id, attempt) -> admit(id, effective, attempt), Clock.systemDefaultZone()).withCalibratedCosts();
    }

    /** Revalidate after waiting for admission: the scheduler's snapshot may be stale. */
    public FleetJobHandle admit(String id, AutoDispatch policy) {
        return admit(id, policy, null);
    }

    private FleetJobHandle admit(String id, AutoDispatch policy, LaunchAttempt attempt) {
        Path repo = FleetControl.repoRootOf(model.root());
        return DispatchGuard.admit(repo, policy.maxConcurrent(), () -> {
            requireActive();
            List<Task> scope = model.projectTasks();
            List<Task> candidate = scope.stream().filter(task -> id.equals(task.id))
                    .filter(task -> BoardModel.BACKLOG.equals(model.sprint())
                            ? task.sprint == null : model.sprint().equals(task.sprint))
                    .toList();
            CostOverview cost = CostOverview.of(scope);
            var plan = policy.withEstimateUsd(AutoDispatch.calibratedEstimate(cost)).plan(candidate,
                    StageReadiness.evaluate(scope), cost, DispatchGuard.runningIds(repo));
            if (!plan.launch().contains(id)) {
                String reason = plan.skipped().stream().filter(skip -> id.equals(skip.id()))
                        .map(AutoDispatch.Skip::reason).findFirst().orElse("ticket left the selected sprint");
                throw new DispatchGuard.AdmissionDeferred("ticket no longer admissible: " + id + " — " + reason);
            }
            requireActive();
            return requireAccepted(id, attempt == null
                    ? launcher.launchAuto(model.project(), id, policy.includeStale())
                    : launcher.launchAuto(model.project(), id, policy.includeStale(), attempt));
        });
    }

    /** Check cancellation after acquiring admission, then reserve before releasing it. */
    public FleetJobHandle launch(String id) {
        return DispatchGuard.exclusive(FleetControl.repoRootOf(model.root()), () -> launchUnderAdmission(id));
    }

    private FleetJobHandle launchUnderAdmission(String id) {
        requireActive();
        return requireAccepted(id, launcher.launch(model.project(), id));
    }

    private static FleetJobHandle requireAccepted(String id, FleetJobHandle job) {
        if (job == null || job.failed()) {
            throw new IllegalStateException("launch refused for " + id + ": "
                    + (job == null ? "no job returned" : job.detail()));
        }
        return job;
    }

    private void requireActive() {
        if (cancelled.getAsBoolean()) {
            throw new DispatchGuard.AdmissionDeferred("Board dispatch stopped or selection changed");
        }
    }

    public static String summary(AutoDispatch.DispatchPlan plan) {
        StringBuilder text = new StringBuilder("Launched ").append(plan.launch().size())
                .append(", skipped ").append(plan.skipped().size()).append(".");
        for (AutoDispatch.Skip skip : plan.skipped()) {
            text.append("\n").append(skip.id()).append(" — ").append(skip.reason());
        }
        return text.toString();
    }
}
