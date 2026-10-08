package ar.emedicapp.purchase;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;

/** Daily local schedule; absence means availability is confirmed manually by the branch. */
public final class BranchAvailability {
    private BranchAvailability() {}

    public static boolean open(Map<String, Object> caps, LocalTime time) {
        Object start = caps.get("opensAt"), end = caps.get("closesAt");
        if (start == null && end == null) return true;
        if (start == null || end == null) return false;
        try {
            LocalTime opens = LocalTime.parse(start.toString()),
                    closes = LocalTime.parse(end.toString());
            if (opens.equals(closes)) return false;
            return opens.isBefore(closes)
                    ? !time.isBefore(opens) && time.isBefore(closes)
                    : !time.isBefore(opens) || time.isBefore(closes);
        } catch (java.time.format.DateTimeParseException invalid) {
            return false;
        }
    }

    public static boolean withinRadius(Map<String, Object> caps, double distance) {
        try {
            BigDecimal radius =
                    new BigDecimal(String.valueOf(caps.getOrDefault("deliveryRadiusKm", "20")));
            return radius.signum() > 0 && BigDecimal.valueOf(distance).compareTo(radius) <= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
