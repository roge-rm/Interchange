// Host tests for the music: the same seed plays the same, no style clips, phrases vary, pieces
// have quiet gaps, winter is sparser, slower and lower, alerts duck it, and off costs nothing.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <map>
#include <set>
#include <thread>

#include "host.h"

using namespace host;

namespace {

std::atomic<int> failures{0};

void check(bool ok, const char* what) {
    if (!ok) { std::printf("FAIL %s\n", what); ++failures; }
}

const int kYears[musicStyle::COUNT] = {1905, 1935, 1965, 1990, 2020, 2060};
const char* kStyleNames[musicStyle::COUNT] = {"parlour", "swing", "sixties", "synth pop", "ambient", "future"};
const char* kSeasonNames[4] = {"spring", "summer", "autumn", "winter"};

/** Every note composed, through the hook. */
struct Notes {
    std::vector<MusicNote> all;
    static void hook(void* user, const MusicNote& n) { static_cast<Notes*>(user)->all.push_back(n); }
    Driver driver() {
        return [this](float t, Scene&, Synth& synth) {
            if (t == 0) { synth.music().noteHook = &Notes::hook; synth.music().hookUser = this; }
        };
    }
};

void sameSeedSameMusic() {
    std::vector<float> a = music(40, 1965, musicSeason::SUMMER, 7);
    std::vector<float> b = music(40, 1965, musicSeason::SUMMER, 7);
    std::vector<float> c = music(40, 1965, musicSeason::SUMMER, 8);
    check(a == b, "the same seed renders the same samples");
    check(a != c, "another seed renders other music");
}

void yearsPickStyles() {
    for (int s = 0; s < musicStyle::COUNT; ++s) {
        char what[64];
        std::snprintf(what, sizeof what, "%d plays %s", kYears[s], kStyleNames[s]);
        check(musicStyleOf(kYears[s]) == s, what);
    }
    check(musicStyleOf(1850) == musicStyle::PARLOUR && musicStyleOf(2300) == musicStyle::FUTURE, "years past either end");
    Notes notes;
    music(5, 1990, musicSeason::AUTUMN, 3, notes.driver());
    check(!notes.all.empty() && notes.all[0].style == musicStyle::SYNTH_POP && notes.all[0].season == musicSeason::AUTUMN,
          "the notes come in the year's style and season");
}

/** Every style and season: sounds, finite, under clipping and at a background level. */
void noClipping() {
    for (int s = 0; s < musicStyle::COUNT; ++s) {
        for (int season = 0; season < 4; ++season) {
            std::vector<float> raw = music(50, kYears[s], season, 11 + s, nullptr, false);
            float db = rmsDb(raw, static_cast<size_t>(5 * kRate) * 2);
            float p = peak(raw);
            char what[128];
            std::snprintf(what, sizeof what, "%s %s finite, peak %.2f under 0.8, %.1f dB RMS in -34..-20", kStyleNames[s], kSeasonNames[season], p, db);
            check(allFinite(raw) && p < 0.8f && db > -34.0f && db < -20.0f, what);
        }
    }
}

/** Two-bar melody phrases rarely come back exactly, and no two pieces open alike. */
void phrasesVary() {
    std::vector<std::thread> styles;
    for (int s = 0; s < musicStyle::COUNT; ++s) styles.emplace_back([s] {
        Notes notes;
        // Long enough for three pieces or so; the slow styles take longer.
        music(s >= musicStyle::AMBIENT ? 600.0f : 400.0f, kYears[s], musicSeason::SUMMER, 21, notes.driver());
        std::map<std::pair<int, int>, std::vector<int>> phrases;  // (piece, bar / 2) to steps and notes
        std::map<int, std::vector<int>> openings;
        for (const MusicNote& n : notes.all) {
            if (n.part != musicPart::MELODY) continue;
            std::vector<int>& p = phrases[{n.piece, n.bar / 2}];
            p.push_back((n.bar % 2) * 16 + n.step);
            p.push_back(n.midi);
            std::vector<int>& o = openings[n.piece];
            if (o.size() < 16) { o.push_back(n.bar * 16 + n.step); o.push_back(n.midi); }
        }
        std::set<std::vector<int>> distinct;
        for (const auto& [key, p] : phrases) distinct.insert(p);
        std::set<std::vector<int>> distinctOpenings;
        for (const auto& [piece, o] : openings) distinctOpenings.insert(o);
        float share = phrases.empty() ? 0 : float(distinct.size()) / phrases.size();
        char what[128];
        std::snprintf(what, sizeof what, "%s: %zu of %zu phrases differ (%.0f%%), %zu pieces", kStyleNames[s], distinct.size(), phrases.size(), 100 * share, openings.size());
        check(phrases.size() >= 20 && share >= 0.75f && openings.size() >= 2, what);
        check(distinctOpenings.size() == openings.size(), "no two pieces open alike");
    });
    for (std::thread& t : styles) t.join();
}

/** Between pieces the music rests for a while, quietly. */
void gapsBetweenPieces() {
    for (int season : {musicSeason::SUMMER, musicSeason::WINTER}) {
        std::vector<int> playing;  // per kStep
        std::vector<float> s = music(480, 1935, season, 5, [&](float, Scene&, Synth& synth) { playing.push_back(synth.music().playing()); });
        int longest = 0, run = 0, quietGaps = 0;
        size_t steps = playing.size();
        for (size_t i = 0; i <= steps; ++i) {
            if (i < steps && !playing[i]) { ++run; continue; }
            if (run > 0 && i < steps) {
                // A gap that ended inside the render: quiet after the tail's few seconds.
                size_t from = static_cast<size_t>((i - run) * kStep * kRate + 4 * kRate) * 2;
                size_t to = static_cast<size_t>(i * kStep * kRate) * 2;
                if (to > from && rmsDb(s, from, to) < -60.0f && run * kStep >= 15.0f) ++quietGaps;
            }
            longest = std::max(longest, run);
            run = 0;
        }
        char what[96];
        std::snprintf(what, sizeof what, "%s: %d quiet gaps of 15 s or more in 8 minutes, longest %.0f s", kSeasonNames[season], quietGaps, longest * kStep);
        check(quietGaps >= 2, what);
    }
}

/** Winter plays fewer notes, slower and lower than summer, in every style. */
void winterSparserAndLower() {
    for (int s = 0; s < musicStyle::COUNT; ++s) {
        double rate[2] = {}, pitch[2] = {};
        for (int w = 0; w < 2; ++w) {
            int season = w ? musicSeason::WINTER : musicSeason::SUMMER;
            int count = 0, melody = 0;
            double sum = 0;
            for (uint32_t seed = 1; seed <= 2; ++seed) {
                Notes notes;
                music(60, kYears[s], season, seed, notes.driver());
                count += static_cast<int>(notes.all.size());
                for (const MusicNote& n : notes.all) if (n.part == musicPart::MELODY || n.part == musicPart::CHORD) { sum += n.midi; ++melody; }
            }
            rate[w] = count / 2.0;
            pitch[w] = melody ? sum / melody : 0;
        }
        char what[128];
        std::snprintf(what, sizeof what, "%s: winter %.0f notes a minute against summer %.0f, pitch %.1f against %.1f", kStyleNames[s], rate[1], rate[0], pitch[1], pitch[0]);
        check(rate[1] < 0.8 * rate[0] && pitch[1] < pitch[0] - 1.0, what);
    }
}

/** A news chime pulls the music down about 10 dB and lets it back; a click doesn't. */
void alertsDuck() {
    auto withShot = [](int r, float kind) {
        return [r, kind](float t, Scene&, Synth& synth) {
            if (t == 0) {
                // The UI bus off, so what's heard is the music alone.
                const float gains[bus::COUNT] = {1, 1, 0, 1};
                synth.setBusGains(gains);
            }
            if (std::fabs(t - 20.0f) < kStep / 2) synth.pushEvent(shot(r, {kind}, 0.5f));
        };
    };
    float duckDuring = 1, duckAfter = 0, clickDuck = 1;
    std::vector<float> plain = music(26, 1965, musicSeason::SUMMER, 9, withShot(recipe::CLICK, 0));
    std::vector<float> ducked = music(26, 1965, musicSeason::SUMMER, 9, [&](float t, Scene& sc, Synth& synth) {
        withShot(recipe::CHIME, 1)(t, sc, synth);
        if (std::fabs(t - 20.5f) < kStep / 2) duckDuring = synth.musicDuck();
        if (std::fabs(t - 25.0f) < kStep / 2) duckAfter = synth.musicDuck();
    });
    music(22, 1965, musicSeason::SUMMER, 9, [&](float t, Scene& sc, Synth& synth) {
        withShot(recipe::CLICK, 0)(t, sc, synth);
        if (std::fabs(t - 20.05f) < kStep / 2) clickDuck = synth.musicDuck();
    });
    size_t from = static_cast<size_t>(20.3f * kRate) * 2, to = static_cast<size_t>(20.9f * kRate) * 2;
    float drop = rmsDb(plain, from, to) - rmsDb(ducked, from, to);
    char what[128];
    std::snprintf(what, sizeof what, "a chime ducks the music %.1f dB (duck %.2f, then %.2f)", drop, duckDuring, duckAfter);
    check(drop > 7.0f && duckDuring < 0.4f && duckAfter > 0.9f, what);
    check(clickDuck > 0.99f, "a click doesn't duck the music");
}

/** With the music bus at 0 nothing is worked out, and year 0 fades a piece out. */
void offIsOff() {
    int64_t early = -1, late = -2;
    std::vector<float> off = music(20, 1965, musicSeason::SUMMER, 3, [&](float t, Scene&, Synth& synth) {
        if (t == 0) {
            const float gains[bus::COUNT] = {1, 1, 1, 0};
            synth.setBusGains(gains);
        }
        if (std::fabs(t - 1.0f) < kStep / 2) early = synth.music().time();
        late = synth.music().time();
    });
    // After the room's tail of the first moment.
    float offPeak = peak(std::vector<float>(off.begin() + static_cast<long>(8 * kRate) * 2, off.end()));
    char what[96];
    std::snprintf(what, sizeof what, "music off is silent (peak %g) and idle (%lld then %lld)", offPeak, (long long)early, (long long)late);
    check(offPeak < 1e-5f && early == late, what);

    bool stillPlaying = true;
    std::vector<float> stopped = music(20, 1965, musicSeason::SUMMER, 3, [&](float t, Scene&, Synth& synth) {
        if (std::fabs(t - 10.0f) < kStep / 2) synth.setMusic(0, 0, 3);
        if (std::fabs(t - 19.0f) < kStep / 2) stillPlaying = synth.music().playing();
    });
    size_t after = static_cast<size_t>(17 * kRate) * 2;
    float before = rmsDb(stopped, 0, static_cast<size_t>(10 * kRate) * 2), later = rmsDb(stopped, after);
    std::snprintf(what, sizeof what, "year 0 fades the music out: %.1f dB, then %.1f dB, playing %d", before, later, stillPlaying);
    check(!stillPlaying && later < -60.0f && before > -40.0f, what);
}

void cost() {
    auto start = std::chrono::steady_clock::now();
    music(120, 1990, musicSeason::SUMMER, 4);
    double took = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    std::printf("music (synth pop, summer, the busiest): %.2f%% of one core\n", 100.0 * took / 120.0);
}

}  // namespace

int main() {
    // Each test on its own thread, as they're long renders. The cost is timed alone after.
    std::vector<std::thread> tests;
    for (void (*test)() : {sameSeedSameMusic, yearsPickStyles, noClipping, phrasesVary, gapsBetweenPieces, winterSparserAndLower, alertsDuck, offIsOff})
        tests.emplace_back(test);
    for (std::thread& t : tests) t.join();
    cost();
    if (failures) { std::printf("%d failed\n", failures.load()); return 1; }
    std::printf("music host tests passed\n");
    return 0;
}
