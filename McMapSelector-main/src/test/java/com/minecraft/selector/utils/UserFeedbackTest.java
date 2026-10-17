package com.minecraft.selector.utils;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserFeedbackTest {
    @Test void benignCancelDetection() {
        assertTrue(UserFeedback.isBenignCancel(new CancellationException("cancel")));
        assertTrue(UserFeedback.isBenignCancel(new InterruptedException("interrupt")));
        assertTrue(UserFeedback.isBenignCancel(new RuntimeException(new CancellationException())));
        assertFalse(UserFeedback.isBenignCancel(new IllegalStateException("real error")));
        assertFalse(UserFeedback.isBenignCancel(null));
    }
}
