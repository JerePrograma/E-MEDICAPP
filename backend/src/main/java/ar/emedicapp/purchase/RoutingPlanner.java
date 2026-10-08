package ar.emedicapp.purchase;

import java.util.*;

/** Commercial ordering is lexicographic; distance never outranks a paid subscription. */
public final class RoutingPlanner {
    public record Candidate(
            UUID branchId,
            UUID chainId,
            boolean subscribed,
            double distanceKm,
            Map<UUID, Integer> available) {}

    public record Allocation(Candidate candidate, Map<UUID, Integer> quantities) {}

    public List<Allocation> plan(
            Map<UUID, Integer> required,
            List<Candidate> candidates,
            UUID preferredBranch,
            UUID preferredChain,
            boolean fallback) {
        Comparator<Candidate> priority =
                Comparator.comparingInt(
                                (Candidate c) ->
                                        Objects.equals(c.branchId(), preferredBranch)
                                                ? 0
                                                : preferredChain != null
                                                                && Objects.equals(
                                                                        c.chainId(), preferredChain)
                                                        ? 1
                                                        : 2)
                        .thenComparingDouble(Candidate::distanceKm)
                        .thenComparing(c -> c.branchId().toString());
        List<Candidate> paid =
                candidates.stream().filter(Candidate::subscribed).sorted(priority).toList();
        for (Candidate c : paid)
            if (covers(required, List.of(c))) return allocate(required, List.of(c));
        if (covers(required, paid)) return allocate(required, minimumCover(required, paid));
        Map<UUID, Integer> missing = new LinkedHashMap<>(required);
        List<Allocation> result = new ArrayList<>();
        // Exhaust every eligible subscriber before introducing a verified fallback for uncovered
        // units.
        Map<UUID, Integer> subscriberCoverage = new LinkedHashMap<>();
        required.forEach(
                (product, quantity) -> {
                    int possible =
                            (int)
                                    Math.min(
                                            quantity,
                                            paid.stream()
                                                    .mapToLong(
                                                            c ->
                                                                    c.available()
                                                                            .getOrDefault(
                                                                                    product, 0))
                                                    .sum());
                    if (possible > 0) subscriberCoverage.put(product, possible);
                });
        List<Allocation> subscribed =
                allocate(subscriberCoverage, minimumCover(subscriberCoverage, paid));
        for (Allocation a : subscribed) {
            result.add(a);
            a.quantities().forEach((id, q) -> missing.compute(id, (k, v) -> v - q));
        }
        missing.values().removeIf(v -> v == 0);
        if (!fallback) return List.of();
        List<Candidate> last =
                candidates.stream().filter(c -> !c.subscribed()).sorted(priority).toList();
        if (!covers(missing, last)) return List.of();
        result.addAll(allocate(missing, minimumCover(missing, last)));
        return List.copyOf(result);
    }

    private boolean covers(Map<UUID, Integer> required, List<Candidate> candidates) {
        return required.entrySet().stream()
                .allMatch(
                        e ->
                                candidates.stream()
                                                .mapToLong(
                                                        c ->
                                                                c.available()
                                                                        .getOrDefault(
                                                                                e.getKey(), 0))
                                                .sum()
                                        >= e.getValue());
    }

    private List<Candidate> minimumCover(Map<UUID, Integer> required, List<Candidate> candidates) {
        List<Candidate> useful =
                candidates.stream()
                        .filter(
                                c ->
                                        required.entrySet().stream()
                                                .anyMatch(
                                                        e ->
                                                                c.available()
                                                                                .getOrDefault(
                                                                                        e.getKey(),
                                                                                        0)
                                                                        > 0))
                        .toList();
        // Find the smallest covering subset; deterministic iteration retains chain/distance
        // preference.
        for (int size = 1; size <= useful.size(); size++) {
            List<Candidate> selected = new ArrayList<>();
            if (find(required, useful, 0, size, selected)) return List.copyOf(selected);
        }
        return useful;
    }

    private boolean find(
            Map<UUID, Integer> required,
            List<Candidate> all,
            int index,
            int left,
            List<Candidate> selected) {
        if (left == 0) return covers(required, selected);
        if (all.size() - index < left) return false;
        List<Candidate> possible = new ArrayList<>(selected);
        possible.addAll(all.subList(index, all.size()));
        if (!covers(required, possible)) return false;
        for (int i = index; i <= all.size() - left; i++) {
            selected.add(all.get(i));
            if (find(required, all, i + 1, left - 1, selected)) return true;
            selected.removeLast();
        }
        return false;
    }

    private List<Allocation> allocate(Map<UUID, Integer> required, List<Candidate> candidates) {
        Map<UUID, Integer> remaining = new LinkedHashMap<>(required);
        List<Allocation> result = new ArrayList<>();
        for (Candidate c : candidates) {
            Map<UUID, Integer> assigned = new LinkedHashMap<>();
            remaining.forEach(
                    (p, q) -> {
                        int n = Math.min(q, c.available().getOrDefault(p, 0));
                        if (n > 0) assigned.put(p, n);
                    });
            assigned.forEach((p, q) -> remaining.compute(p, (k, v) -> v - q));
            if (!assigned.isEmpty()) result.add(new Allocation(c, Map.copyOf(assigned)));
        }
        return List.copyOf(result);
    }

    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double a =
                Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.pow(Math.sin(Math.toRadians(lon2 - lon1) / 2), 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
