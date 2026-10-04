package network.vonix.serverutilities.donation_ranks;

import org.junit.jupiter.api.Test;

/**
 * JUnit entry for the NeoForge unit-test harness. The session listener installs
 * a current FML loader before this method runs, so {@code ServerPlayer}
 * initialization can execute the public {@code RankSyncTask.onJoin} hook.
 * The assertions themselves stay in {@link DonationRankOwnershipGateTest}.
 */
public final class DonationRankOwnershipGateJUnit {
    @Test
    public void donationRankOwnershipGate() throws Exception {
        DonationRankOwnershipGateTest.main(new String[0]);
    }
}
