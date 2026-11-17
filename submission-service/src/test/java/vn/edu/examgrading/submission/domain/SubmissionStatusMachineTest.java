package vn.edu.examgrading.submission.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SubmissionStatusMachineTest {

    @Test
    void allowsTheHappyUploadPath() {
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.UPLOADING, SubmissionStatus.UPLOADED));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.UPLOADED, SubmissionStatus.VALIDATING));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.VALIDATING, SubmissionStatus.ANONYMIZING));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.ANONYMIZING, SubmissionStatus.READY_FOR_GRADING));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.READY_FOR_GRADING, SubmissionStatus.GRADING));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.GRADING, SubmissionStatus.COMPLETED));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.COMPLETED, SubmissionStatus.FINALIZED));
    }

    @Test
    void allowsReviewAndFailureEdges() {
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.GRADING, SubmissionStatus.REVIEW_REQUIRED));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.REVIEW_REQUIRED, SubmissionStatus.FINALIZED));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.UPLOADING, SubmissionStatus.FAILED));
        assertTrue(SubmissionStatusMachine.canTransition(SubmissionStatus.FAILED, SubmissionStatus.VALIDATING));
    }

    @Test
    void rejectsIllegalTransitions() {
        assertFalse(SubmissionStatusMachine.canTransition(SubmissionStatus.UPLOADING, SubmissionStatus.GRADING));
        assertFalse(SubmissionStatusMachine.canTransition(SubmissionStatus.UPLOADED, SubmissionStatus.UPLOADED));
        assertFalse(SubmissionStatusMachine.canTransition(SubmissionStatus.FINALIZED, SubmissionStatus.UPLOADED));
        assertFalse(SubmissionStatusMachine.canTransition(SubmissionStatus.FAILED, SubmissionStatus.UPLOADED));
        assertFalse(SubmissionStatusMachine.canTransition(null, SubmissionStatus.UPLOADED));
    }

    @Test
    void transitionReturnsTargetWhenLegalAndThrowsOtherwise() {
        assertSame(SubmissionStatus.UPLOADED,
                SubmissionStatusMachine.transition(SubmissionStatus.UPLOADING, SubmissionStatus.UPLOADED));
        assertThrows(IllegalArgumentException.class, () -> SubmissionStatusMachine.transition(
                SubmissionStatus.UPLOADING, SubmissionStatus.COMPLETED));
    }
}
