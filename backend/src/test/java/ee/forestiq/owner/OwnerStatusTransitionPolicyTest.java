package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OwnerStatusTransitionPolicyTest {

    @Test
    void allowsNormalManualTransition() {
        assertThat(OwnerService.isManualStatusAllowed(OwnerStatus.NEW, OwnerStatus.CONTACTED)).isTrue();
    }

    @Test
    void rejectsWorkflowManagedEvaluationTargets() {
        assertThat(OwnerService.isManualStatusAllowed(OwnerStatus.NEW, OwnerStatus.EVALUATION)).isFalse();
        assertThat(OwnerService.isManualStatusAllowed(OwnerStatus.NEW, OwnerStatus.WAITS_FOR_EVALUATION)).isFalse();
    }

    @Test
    void rejectsLeavingDeceasedStateAndNoOpTransitions() {
        assertThat(OwnerService.isManualStatusAllowed(OwnerStatus.DECEASED, OwnerStatus.CONTACTED)).isFalse();
        assertThat(OwnerService.isManualStatusAllowed(OwnerStatus.CONTACTED, OwnerStatus.CONTACTED)).isFalse();
    }
}
