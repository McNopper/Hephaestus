package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the {@link ReviewVerdict} parser: the three decisions with
 * and without reasons, the LAST verdict line winning, decoration- and
 * case-tolerance, and the null contract for replies without a verdict.
 */
public class ReviewVerdictTest {

    @Test
    public void parsesTheThreeDecisionsWithReasons() {
        assertEquals(ReviewVerdict.Decision.PASS,
                ReviewVerdict.parse("all good.\nVERDICT: PASS - every criterion verified").decision());
        assertEquals(ReviewVerdict.Decision.FAIL,
                ReviewVerdict.parse("VERDICT: FAIL - criterion 1 unmet; gate red").decision());
        assertEquals(ReviewVerdict.Decision.UNCLEAR,
                ReviewVerdict.parse("VERDICT: UNCLEAR - gate unreachable").decision());
    }

    @Test
    public void reasonIsTheTextAfterTheDecisionWord() {
        assertEquals("every criterion verified, gate green",
                ReviewVerdict.parse("VERDICT: PASS - every criterion verified, gate green").reason());
        assertEquals("separators of every tolerated shape are trimmed",
                "criterion 2 unmet",
                ReviewVerdict.parse("VERDICT: FAIL: criterion 2 unmet").reason());
        assertNull("no reason is null, not blank",
                ReviewVerdict.parse("VERDICT: PASS").reason());
        assertEquals("a bare FAIL still yields actionable text for the send-back",
                "reviewer gave no reasons",
                ReviewVerdict.parse("VERDICT: FAIL").reasonOrDefault());
    }

    @Test
    public void lastVerdictLineWinsAndCaseAndDecorationAreTolerated() {
        assertEquals("the protocol text quoted mid-reply must not win",
                ReviewVerdict.Decision.FAIL,
                ReviewVerdict.parse("the prompt says VERDICT: PASS means fine\n"
                        + "but criterion 1 is unmet\nVERDICT: FAIL - criterion 1").decision());
        assertEquals("lower-case verdict lines parse",
                ReviewVerdict.Decision.PASS,
                ReviewVerdict.parse("verdict: pass - ok").decision());
        assertEquals("markdown decoration (bold, bullets) is stripped",
                ReviewVerdict.Decision.UNCLEAR,
                ReviewVerdict.parse("- **VERDICT**: UNCLEAR - no CI evidence").decision());
    }

    @Test
    public void repliesWithoutAVerdictLineParseToNull() {
        assertNull(ReviewVerdict.parse(null));
        assertNull(ReviewVerdict.parse(""));
        assertNull(ReviewVerdict.parse("  "));
        assertNull(ReviewVerdict.parse("looks fine to me"));
        assertNull("a verdict word without the VERDICT: prefix is not a verdict line",
                ReviewVerdict.parse("final answer: pass"));
        assertNull("an unknown decision is not a verdict line",
                ReviewVerdict.parse("VERDICT: MAYBE - could go either way"));
    }
}
