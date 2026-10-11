package outside.namespace;

/**
 * U-083 seeded-proof fixture: a class deliberately OUTSIDE the com.opencode.ide namespace. {@code
 * LayerArchitectureTest} imports this class and asserts that the namespace rule rejects it - the
 * permanent proof that the rule can actually fail.
 */
public final class OutsideNamespace {

  private OutsideNamespace() {}

  /**
   * @return this fixture's own name (keeps the class from being optimized away)
   */
  public String name() {
    return OutsideNamespace.class.getName();
  }
}
