package com.tvhome.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Cricket scorecards from CricAPI. Pure data class: every call is blocking and
 * must be run off the main thread by the caller.
 *
 * Priority fixtures are top international sides (men and women) plus the major franchise
 * and domestic tournaments (IPL, PSL, CPL, BBL, The Hundred and friends). Minor associate
 * fixtures are only used when nothing higher exists, and the ticker is capped at
 * {@link #MAX_DISPLAY} matches so the marquee stays readable.
 */
public final class Cricket {

    /** Free API key from https://cricapi.com — paste it here to enable live data. */
    public static final String API_KEY = "bb6a9d3a-618d-4162-8b2b-02dfc57ab73b";

    /** High-priority fixtures the ticker is allowed to show at once. */
    public static final int MAX_DISPLAY = 2;

    /** Full names of the top international sides; women's teams match on the same stem. */
    private static final List<String> TOP_TEAMS = Arrays.asList(
            "pakistan", "india", "australia", "england", "south africa", "new zealand",
            "west indies", "sri lanka", "bangladesh", "afghanistan", "ireland", "zimbabwe");

    /** The shortnames CricAPI reports for those sides. */
    private static final List<String> TOP_SHORTNAMES = Arrays.asList(
            "pak", "ind", "aus", "eng", "rsa", "sa", "nz", "nzl", "wi", "sl", "ban",
            "afg", "ire", "zim");

    /**
     * Franchise and first-class tournaments that count as priority fixtures. Matched with
     * word boundaries so short codes like "cpl" never match inside an unrelated word.
     */
    private static final List<String> LEAGUES = Arrays.asList(
            "indian premier league", "ipl", "pakistan super league", "psl",
            "caribbean premier league", "cpl", "big bash league", "bbl",
            "the hundred", "hundred", "sa20", "international league t20", "ilt20",
            "lanka premier league", "lpl", "bangladesh premier league", "bpl",
            "nepal premier league", "major league cricket", "global super league",
            "women's premier league", "wpl", "women's big bash", "wbbl",
            "ranji trophy", "vijay hazare", "sheffield shield", "plunket shield",
            "curran cup", "csa four-day", "super smash", "ford trophy");

    private static final java.util.regex.Pattern[] LEAGUE_PATTERNS = buildLeaguePatterns();

    /** Wording that means an innings result is settled, so the match is no longer live. */
    private static final String[] SETTLED = {
            "won", "tied", "no result", "abandon", "cancel", "draw", "super over", "definitive"};

    /** 0 = top-side international, 1 = major league or first-class, 2 = everything else. */
    public static final class Match {
        public final String title;
        public final String status;
        public final String score;
        /** Compact widget line: "PAK 180/4 (20)  vs  AUS 120/2 (14.2)". */
        public final String chip;
        public final boolean live;
        public final int tier;
        public final String time;

        Match(String title, String status, String score, String chip, boolean live, int tier,
              String time) {
            this.title = title;
            this.status = status;
            this.score = score;
            this.chip = chip;
            this.live = live;
            this.tier = tier;
            this.time = time;
        }
    }

    private Cricket() {
    }

    /** Feed 0: matches currently in progress or starting soon. */
    public static List<Match> fetchLive() throws Exception {
        return fetch("currentMatches");
    }

    /** Feed 1: recent, finished and upcoming international matches. */
    public static List<Match> fetchAll() throws Exception {
        return fetch("matches");
    }

    /** Header badge: LIVE only while at least one shown fixture is genuinely under way. */
    public static String badgeFor(List<Match> matches) {
        for (Match match : matches) {
            if (match.live) {
                return "LIVE";
            }
        }
        return "CRICKET";
    }

    private static List<Match> fetch(String endpoint) throws Exception {
        if (API_KEY.trim().isEmpty()) {
            throw new IllegalStateException("no CricAPI key configured");
        }
        String json = httpGet("https://api.cricapi.com/v1/" + endpoint
                + "?apikey=" + API_KEY + "&offset=0");
        JSONObject root = new JSONObject(json);
        if (!"success".equalsIgnoreCase(root.optString("status"))) {
            throw new IllegalStateException("feed error: " + root.optString("reason", "unknown"));
        }
        JSONArray data = root.optJSONArray("data");
        List<Match> parsed = new ArrayList<>();
        if (data == null) {
            return select(parsed);
        }
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Match match = parse(item);
            if (match != null) {
                parsed.add(match);
            }
        }
        return select(parsed);
    }

    /** One completed innings line plus the full team name it was credited to. */
    private static final class Innings {
        final String line;
        final String team;

        Innings(String line, String team) {
            this.line = line;
            this.team = team;
        }
    }

    private static Match parse(JSONObject item) {
        List<String> teams = teamNames(item.optJSONArray("teams"));
        java.util.Map<String, String> shortnames = shortnames(item.optJSONArray("teamInfo"));
        List<Innings> innings = scoreLabels(item, teams, shortnames);
        List<String> labels = new ArrayList<>();
        for (Innings in : innings) {
            labels.add(in.line);
        }
        String status = item.optString("status", "").trim();
        boolean started = item.optBoolean("matchStarted", false);
        boolean live = started && !isSettled(status);
        int tier = tierOf(item.optString("name", ""), teams, item.optJSONArray("teamInfo"));
        String score = labels.isEmpty() ? status : joinLabels(labels);
        if (score.isEmpty()) {
            score = "Match not started";
        }
        String chip = chip(innings, teams, shortnames, item.optString("name", "Cricket"));
        return new Match(item.optString("name", "Cricket"), status, score, chip, live, tier,
                item.optString("dateTimeGMT", ""));
    }

    /**
     * Builds one line per innings using the team shortnames rather than the API's messy
     * "inning" strings, which are inconsistently cased and sometimes list both sides.
     */
    private static List<Innings> scoreLabels(JSONObject item, List<String> teams,
                                             java.util.Map<String, String> shortnames) {
        List<Innings> out = new ArrayList<>();
        JSONArray innings = item.optJSONArray("score");
        if (innings == null || innings.length() == 0 || teams.isEmpty()) {
            return out;
        }
        java.util.Map<String, Integer> used = new java.util.LinkedHashMap<>();
        for (String team : teams) {
            used.put(team, 0);
        }
        for (int i = 0; i < innings.length(); i++) {
            JSONObject in = innings.optJSONObject(i);
            if (in == null) {
                continue;
            }
            String label = in.optString("inning", "").toLowerCase(Locale.US);
            String team = resolveTeam(teams, label, used, i);
            int runs = in.optInt("r", -1);
            int wickets = in.optInt("w", -1);
            double overs = in.optDouble("o", -1);
            StringBuilder line = new StringBuilder(display(team, shortnames));
            if (runs >= 0 && wickets >= 0) {
                line.append(' ').append(runs).append('/').append(wickets);
            } else if (runs >= 0) {
                line.append(' ').append(runs);
            }
            if (overs >= 0) {
                line.append(" (").append(formatOvers(overs)).append(')');
            }
            out.add(new Innings(line.toString(), team));
            Integer count = used.get(team);
            used.put(team, count == null ? 1 : count + 1);
        }
        return out;
    }

    /** Picks the side an innings belongs to, preferring one not yet credited. */
    private static String resolveTeam(List<String> teams, String label,
                                      java.util.Map<String, Integer> used, int index) {
        List<String> candidates = new ArrayList<>();
        for (String team : teams) {
            if (label.contains(team.toLowerCase(Locale.US))) {
                candidates.add(team);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            return teams.get(index % teams.size());
        }
        for (String candidate : candidates) {
            Integer count = used.get(candidate);
            if (count == null || count == 0) {
                return candidate;
            }
        }
        String least = candidates.get(0);
        for (String candidate : candidates) {
            if (count(used, candidate) < count(used, least)) {
                least = candidate;
            }
        }
        return least;
    }

    private static int count(java.util.Map<String, Integer> used, String team) {
        Integer value = used.get(team);
        return value == null ? 0 : value;
    }

    private static String joinLabels(List<String> labels) {
        StringBuilder out = new StringBuilder();
        for (String label : labels) {
            if (out.length() > 0) {
                out.append("   ·   ");
            }
            out.append(label);
        }
        return out.toString();
    }

    /**
     * Compact scorecard line for the header widget: "PAK 180/4 (20)  vs  AUS 120/2 (14.2)".
     * At most two innings are shown; when only one side has batted the other side's
     * shortname is appended so the chip still reads as a pairing.
     */
    private static String chip(List<Innings> innings, List<String> teams,
                               java.util.Map<String, String> shortnames, String title) {
        if (!innings.isEmpty()) {
            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (Innings in : innings) {
                if (shown >= 2) {
                    break;
                }
                if (out.length() > 0) {
                    out.append("  vs  ");
                }
                out.append(in.line.replace(" ov", ""));
                shown++;
            }
            if (innings.size() == 1 && teams.size() > 1) {
                String credited = innings.get(0).team.toLowerCase(Locale.US);
                for (String team : teams) {
                    if (!team.toLowerCase(Locale.US).equals(credited)) {
                        out.append("  vs  ").append(display(team, shortnames));
                        break;
                    }
                }
            }
            return out.toString();
        }
        List<String> sides = new ArrayList<>();
        for (int i = 0; i < Math.min(2, teams.size()); i++) {
            sides.add(display(teams.get(i), shortnames));
        }
        if (sides.isEmpty()) {
            return title;
        }
        StringBuilder out = new StringBuilder(sides.get(0));
        if (sides.size() > 1) {
            out.append("  vs  ").append(sides.get(1));
        }
        return out.toString();
    }

    private static List<String> teamNames(JSONArray teams) {
        List<String> out = new ArrayList<>();
        if (teams == null) {
            return out;
        }
        for (int i = 0; i < teams.length(); i++) {
            String name = teams.optString(i, "").trim();
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    /** teamInfo[].shortname when CricAPI supplies one (PAK, AUS, IND), otherwise the full name. */
    private static java.util.Map<String, String> shortnames(JSONArray teamInfo) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (teamInfo == null) {
            return out;
        }
        for (int i = 0; i < teamInfo.length(); i++) {
            JSONObject info = teamInfo.optJSONObject(i);
            if (info == null) {
                continue;
            }
            String name = info.optString("name", "").trim();
            String shortname = info.optString("shortname", "").trim();
            if (!name.isEmpty() && !shortname.isEmpty()) {
                out.put(name.toLowerCase(Locale.US), shortname.toUpperCase(Locale.US));
            }
        }
        return out;
    }

    private static String display(String team, java.util.Map<String, String> shortnames) {
        String shortname = shortnames.get(team.toLowerCase(Locale.US));
        return shortname == null ? team : shortname;
    }

    private static boolean involvesTopTeam(List<String> teams, JSONArray teamInfo) {
        for (String team : teams) {
            String lower = team.toLowerCase(Locale.US);
            for (String top : TOP_TEAMS) {
                if (lower.contains(top)) {
                    return true;
                }
            }
        }
        if (teamInfo != null) {
            for (int i = 0; i < teamInfo.length(); i++) {
                JSONObject info = teamInfo.optJSONObject(i);
                if (info == null) {
                    continue;
                }
                String shortname = info.optString("shortname", "").toLowerCase(Locale.US);
                String name = info.optString("name", "").toLowerCase(Locale.US);
                if (TOP_SHORTNAMES.contains(shortname)) {
                    return true;
                }
                for (String top : TOP_TEAMS) {
                    if (name.contains(top)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 0 = top-side international, 1 = franchise or first-class tournament,
     * 2 = everything else (shown only when nothing better exists).
     */
    private static int tierOf(String title, List<String> teams, JSONArray teamInfo) {
        if (involvesTopTeam(teams, teamInfo)) {
            return 0;
        }
        return isLeagueFixture(title) ? 1 : 2;
    }

    private static java.util.regex.Pattern[] buildLeaguePatterns() {
        java.util.regex.Pattern[] patterns = new java.util.regex.Pattern[LEAGUES.size()];
        for (int i = 0; i < LEAGUES.size(); i++) {
            String marker = LEAGUES.get(i);
            if (marker.indexOf(' ') < 0) {
                // Short codes get word boundaries so "cpl" never matches inside another word.
                patterns[i] = java.util.regex.Pattern.compile(
                        "(?<![a-z0-9])" + marker + "(?![a-z0-9])");
            }
        }
        return patterns;
    }

    private static boolean isLeagueFixture(String title) {
        String lower = title.toLowerCase(Locale.US);
        for (int i = 0; i < LEAGUES.size(); i++) {
            java.util.regex.Pattern pattern = LEAGUE_PATTERNS[i];
            if (pattern != null ? pattern.matcher(lower).find() : lower.contains(LEAGUES.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Live fixtures first, then top-side internationals, then franchise and first-class
     * tournaments, then earliest start time. Minor associate fixtures are only used when
     * nothing higher is available.
     */
    private static List<Match> select(List<Match> matches) {
        List<Match> pool = new ArrayList<>();
        for (Match match : matches) {
            if (match.tier <= 1) {
                pool.add(match);
            }
        }
        if (pool.isEmpty()) {
            pool.addAll(matches);
        }
        java.util.Collections.sort(pool, (a, b) -> {
            if (a.live != b.live) {
                return a.live ? -1 : 1;
            }
            if (a.tier != b.tier) {
                return a.tier - b.tier;
            }
            int byTime = a.time.compareTo(b.time);
            if (byTime != 0) {
                return byTime;
            }
            return a.title.compareToIgnoreCase(b.title);
        });
        return new ArrayList<>(pool.subList(0, Math.min(MAX_DISPLAY, pool.size())));
    }

    private static boolean isSettled(String status) {
        String lower = status.toLowerCase(Locale.US);
        for (String marker : SETTLED) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String formatOvers(double overs) {
        int whole = (int) overs;
        int balls = (int) Math.round((overs - whole) * 10);
        if (balls == 0) {
            return whole + " ov";
        }
        return whole + "." + balls + " ov";
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "tvhome/1.0");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
