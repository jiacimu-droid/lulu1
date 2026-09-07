import com.jiacimu.lulu.data.DigitalWorldEventRules;

/** Run with javac + java -ea; no Android runtime or model API required. */
public final class DigitalWorldEventRulesTest {
    public static void main(String[] args) {
        long now = 3600L * 500;
        long slot = DigitalWorldEventRules.opportunitySlot(now);
        assert DigitalWorldEventRules.hasNewOpportunity(now, slot - 1, slot - 1);
        // A persisted failed roll, a resolved incident, another visitor and an app restart
        // all preserve the same location slot. Repeated perception cannot reroll it.
        for (int second = 0; second < 3600; second++) {
            assert !DigitalWorldEventRules.hasNewOpportunity(now + second, slot, -1);
            assert !DigitalWorldEventRules.hasNewOpportunity(now + second, -1, slot);
        }
        assert DigitalWorldEventRules.hasNewOpportunity(now + 3600, slot, slot);
        assert !DigitalWorldEventRules.hasNewOpportunity(now - 3600, slot, slot);
        assert !DigitalWorldEventRules.canEvolve(now + 899, now);
        assert DigitalWorldEventRules.canEvolve(now + 900, now);
        assert !DigitalWorldEventRules.canEvolve(now - 1, now);
        for (String kind : new String[]{"roach", "dust_layer", "shelf_tilt", "drawer_jam", "plant_droop"}) {
            assert !DigitalWorldEventRules.naturallyEnds(kind, now + 86400, now) : kind;
        }
        assert !DigitalWorldEventRules.naturallyEnds("glimmer_mote", now + 1799, now);
        assert DigitalWorldEventRules.naturallyEnds("glimmer_mote", now + 1800, now);
        for (String approach : new String[]{"observe", "wait", "avoid", "inspect", "search"}) {
            assert DigitalWorldEventRules.correctionChance("roach", approach) == 0 : approach;
        }
        assert DigitalWorldEventRules.passive("observe");
        assert !DigitalWorldEventRules.passive("drive_out");
        assert DigitalWorldEventRules.correctionChance("roach", "drive_out") > 0;
        assert DigitalWorldEventRules.correctionChance("glimmer_mote", "touch") > 0;
        assert DigitalWorldEventRules.correctionChance("roach", "touch") == 0;
        System.out.println("PASS: opportunity persistence, repeated polls, time reversal, natural changes and action outcomes");
    }
}
