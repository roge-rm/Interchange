// Host tests for the synth: every recipe sounds, stays finite and in range, held sounds let go,
// the voice pool holds its budget, renders repeat exactly, and the new kinds do what they say.
#include <cstdio>

#include "host.h"

using namespace host;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) { std::printf("FAIL %s\n", what); ++failures; }
}

std::vector<float> holdFor(int r, std::initializer_list<float> params, float seconds = 3.0f, float gain = 0.6f) {
    std::vector<float> p(params);
    return run(seconds, [r, p, gain](float, Scene& sc, Synth&) {
        SceneEntry e = held(1, r, {}, gain);
        for (size_t i = 0; i < p.size(); ++i) e.p[i] = p[i];
        sc.entries[sc.count++] = e;
    });
}

void everyRecipeSounds() {
    struct Case { int r; std::vector<float> p; const char* name; };
    std::vector<Case> cases = {
        {recipe::JET, {1, 0.8f, 0.8f}, "jet"}, {recipe::PROP, {1, 80, 0.6f}, "prop"}, {recipe::WIND, {1, 0.5f, 0.5f}, "wind"},
        {recipe::RAIN, {1, 0.5f}, "rain"}, {recipe::FIRE, {1}, "fire"}, {recipe::SEA, {1, 0.5f, 0.2f}, "sea"},
        {recipe::TRAFFIC, {1, 1, 0.5f, 0.5f}, "traffic"}, {recipe::TRAM, {1}, "tram"}, {recipe::BIRDS, {1, 1}, "birds"},
        {recipe::CRICKETS, {1}, "crickets"}, {recipe::CROWD, {1}, "crowd"}, {recipe::SIREN, {1, 0}, "crossing bell"},
        {recipe::HELI, {1, 0.6f}, "helicopter"}, {recipe::DRONE, {1, 0.6f}, "drone"},
        {recipe::CITY, {1, 0.5f, 0.5f}, "town hum"}, {recipe::INDUSTRY, {1, 0.5f, 0.5f}, "industry"},
    };
    for (int k = 0; k < 6; ++k) cases.push_back({recipe::TRAIN, {1, float(k), 0.7f}, "train kind"});
    for (int k = 0; k < 5; ++k) cases.push_back({recipe::OUTBOARD, {1, 0.7f, float(k)}, "ship kind"});
    for (const Case& c : cases) {
        std::vector<float> p = c.p;
        std::vector<float> s = run(3.0f, [&](float, Scene& sc, Synth&) {
            SceneEntry e = held(1, c.r, {}, 0.6f);
            for (size_t i = 0; i < p.size(); ++i) e.p[i] = p[i];
            sc.entries[sc.count++] = e;
        });
        char what[96];
        std::snprintf(what, sizeof what, "%s (%d, %g) sounds, finite, under clipping", c.name, c.r, p.size() > 1 ? p[1] : 0.0f);
        float db = rmsDb(s, s.size() / 3);
        check(allFinite(s) && db > -60.0f && peak(s) <= 1.0f, what);
        // Held sounds sit well under full scale, so a full scene leaves the limiter alone.
        std::snprintf(what, sizeof what, "%s (%d) not too loud: %.1f dB", c.name, c.r, db);
        check(db < -12.0f, what);
    }
}

void oneShotsRingOut() {
    struct Case { int r; std::vector<float> p; };
    std::vector<Case> cases = {{recipe::CHIME, {4}}, {recipe::CHIME, {1}}, {recipe::BELL, {0.8f}}, {recipe::HORN, {0, 0.5f}},
                               {recipe::HORN, {2, 0.5f}}, {recipe::WHISTLE, {0, 0.6f}}, {recipe::THUNDER, {0.8f}}};
    for (const Case& c : cases) {
        int active = -1;
        std::vector<float> s = run(10.0f, [&](float t, Scene&, Synth& synth) {
            if (t == 0) {
                Event e = shot(c.r, {});
                for (size_t i = 0; i < c.p.size(); ++i) e.p[i] = c.p[i];
                synth.pushEvent(e);
            }
            if (t > 9.9f) active = synth.activeVoices();
        });
        char what[64];
        std::snprintf(what, sizeof what, "one-shot %d kind %g sounds and ends", c.r, c.p[0]);
        check(allFinite(s) && rmsDb(s) > -70.0f && peak(s) <= 1.0f && active == 0, what);
    }
}

void heldSoundsLetGo() {
    int active = -1;
    run(3.0f, [&](float t, Scene& sc, Synth& synth) {
        if (t < 1.0f) sc.entries[sc.count++] = held(7, recipe::TRAIN, {1, 4, 0.8f});
        if (t > 2.9f) active = synth.activeVoices();
    });
    check(active == 0, "a held maglev stops once the scene drops it");
}

void budgetHolds() {
    int most = 0;
    run(2.0f, [&](float, Scene& sc, Synth& synth) {
        for (int k = 0; k < 40; ++k) sc.entries[sc.count++] = held(100 + k, recipe::CITY, {1, 0.5f, 0.5f}, 0.1f);
        for (int k = 0; k < 8; ++k) synth.pushEvent(shot(recipe::CLICK, {0}, 0.1f));
        most = std::max(most, synth.activeVoices());
    }, 24);
    check(most <= 24, "the voice pool never goes past its budget");
}

void rendersRepeat() {
    std::vector<float> a = holdFor(recipe::DRONE, {1, 0.5f});
    std::vector<float> b = holdFor(recipe::DRONE, {1, 0.5f});
    check(a == b, "the same scene renders the same samples");
}

/** A maglev's and a pod's rush grows with speed, and neither has a rail joint's clack. */
void floatingKindsRiseWithSpeed() {
    for (int kind : {4, 5}) {
        float slow = rmsDb(holdFor(recipe::TRAIN, {1, float(kind), 0.1f}, 3.0f), 48000);
        float fast = rmsDb(holdFor(recipe::TRAIN, {1, float(kind), 1.0f}, 3.0f), 48000);
        char what[64];
        std::snprintf(what, sizeof what, "kind %d louder at speed: %.1f then %.1f", kind, slow, fast);
        check(fast > slow + 6.0f, what);
    }
}

/** Battery and hydrogen run quieter than a diesel at the same speed. */
void batteryQuieterThanDiesel() {
    float diesel = rmsDb(holdFor(recipe::TRAIN, {1, 1, 0.6f}), 48000);
    float battery = rmsDb(holdFor(recipe::TRAIN, {1, 3, 0.6f}), 48000);
    char what[64];
    std::snprintf(what, sizeof what, "battery %.1f under diesel %.1f", battery, diesel);
    check(battery < diesel - 2.0f, what);
}

/** A drone is a light buzz beside a helicopter. */
void droneUnderHelicopter() {
    float heli = rmsDb(holdFor(recipe::HELI, {1, 0.6f}), 48000);
    float drone = rmsDb(holdFor(recipe::DRONE, {1, 0.6f}), 48000);
    char what[64];
    std::snprintf(what, sizeof what, "drone %.1f under helicopter %.1f", drone, heli);
    check(drone < heli - 4.0f, what);
}

/** From higher up the town hum is darker: less above 1 kHz against its whole. */
void townDarkerFromAfar() {
    auto bright = [](const std::vector<float>& s) {
        // Energy in the first difference against the signal's: a rough tilt measure.
        double d = 0, e = 0;
        for (size_t i = 2; i < s.size(); i += 2) { double x = s[i] - s[i - 2]; d += x * x; e += double(s[i]) * s[i]; }
        return d / std::max(1e-12, e);
    };
    double nearT = bright(holdFor(recipe::CITY, {1, 1, 0}, 4.0f));
    double farT = bright(holdFor(recipe::CITY, {1, 1, 1}, 4.0f));
    check(farT < nearT * 0.8, "the town hum is darker from afar");
}

}  // namespace

int main() {
    everyRecipeSounds();
    oneShotsRingOut();
    heldSoundsLetGo();
    budgetHolds();
    rendersRepeat();
    floatingKindsRiseWithSpeed();
    batteryQuieterThanDiesel();
    droneUnderHelicopter();
    townDarkerFromAfar();
    if (failures) { std::printf("%d failed\n", failures); return 1; }
    std::printf("synth host tests passed\n");
    return 0;
}
