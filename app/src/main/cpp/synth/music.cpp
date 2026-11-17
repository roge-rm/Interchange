#include "music.h"

#include <cmath>

namespace interchange {

namespace {

enum Wave { SINE, FM, SAWS, PULSE, NOISE, KICK };
enum FilterMode { LOW, BAND, HIGH };

/**
 * One instrument. Times in seconds are time constants, except the release, which is about the time to
 * fade. The filter's cutoff is [cutoff] + [keytrack] times the note's frequency + [envAmount] at
 * the note's start, falling over [envTime]. FM [index] is in radians at the start, falling to
 * [indexFloor] of it.
 */
struct Patch {
    int wave;
    float attack, decay, sustain, release;
    float cutoff, keytrack, envAmount, envTime, q;
    int filterMode;
    float detune, width, ratio, index, indexFloor;
    float vibrato, lfoRate, tremolo, breath;
    float level;
};

enum Instrument {
    PIANO, CORNET, CLARINET, VIBES, UPRIGHT, BRUSH, EPIANO, BASS_GUITAR, FLUTE, PAD, SOFT_PAD, REEDS,
    ARP, SYNTH_BASS, LEAD, KICK_DRUM, HAT, RIM, BELL, SUB, GLASS, SHIMMER, PLUCK, INSTRUMENTS
};

// Levels set by ear so each style sits near -26 dB RMS at full music volume.
const Patch kPatches[INSTRUMENTS] = {
    // wave   att    dec   sus   rel     cut   key  env    envT  q     mode  det     wid   ratio idx  floor vib     lfo  trem  breath level
    {SAWS,   0.003f, 1.1f, 0.0f, 0.2f,   300, 1.2f, 2200, 0.3f, 0.7f, LOW, 0.0025f, 0.5f, 1, 0, 0,       0,      5,   0,    0,     0.30f},  // PIANO, a little out of tune
    {SAWS,   0.07f,  0.5f, 0.75f, 0.2f,  250, 1.5f, 700,  0.12f, 0.8f, LOW, 0.001f, 0.5f, 1, 0, 0,       0.004f, 5,   0,    0,     0.16f},  // CORNET, muted
    {PULSE,  0.05f,  0.4f, 0.8f, 0.15f,  200, 2.0f, 400,  0.1f, 0.7f, LOW, 0,       0.5f, 1, 0, 0,       0.003f, 5.2f, 0,   0,     0.17f},  // CLARINET: a square is odd harmonics
    {FM,     0.002f, 2.2f, 0.0f, 0.4f,   0, 0, 0,         0.25f, 0.7f, LOW, 0,      0.5f, 4, 1.0f, 0,    0,      5.5f, 0.3f, 0,    0.26f},  // VIBES, motor on
    {FM,     0.006f, 0.8f, 0.2f, 0.08f,  0, 0, 0,         0.12f, 0.7f, LOW, 0,      0.5f, 1, 1.0f, 0.1f, 0,      5,   0,    0,     0.36f},  // UPRIGHT bass
    {NOISE,  0.015f, 0.14f, 0.0f, 0.05f, 2500, 0, 0,      0.1f, 0.7f, BAND, 0,      0.5f, 1, 0, 0,       0,      5,   0,    0,     0.10f},  // BRUSH
    {FM,     0.003f, 1.8f, 0.0f, 0.3f,   0, 0, 0,         0.4f, 0.7f, LOW, 0,       0.5f, 1, 1.6f, 0.25f, 0,     4,   0.12f, 0,    0.22f},  // EPIANO
    {FM,     0.004f, 1.0f, 0.35f, 0.07f, 0, 0, 0,         0.1f, 0.7f, LOW, 0,       0.5f, 1, 0.9f, 0.15f, 0,     5,   0,    0,     0.36f},  // BASS_GUITAR
    {SINE,   0.09f,  0.5f, 0.85f, 0.2f,  0, 0, 0,         0.1f, 1.0f, BAND, 0,      0.5f, 1, 0, 0,       0.005f, 5,   0,    0.06f, 0.20f},  // FLUTE, with breath
    {SAWS,   0.8f,   2.0f, 0.8f, 1.4f,   180, 0.9f, 0,    0.1f, 0.6f, LOW, 0.005f,  0.5f, 1, 0, 0,       0,      5,   0,    0,     0.07f},  // PAD
    {SAWS,   2.5f,   3.0f, 0.85f, 3.0f,  150, 0.7f, 0,    0.1f, 0.5f, LOW, 0.006f,  0.5f, 1, 0, 0,       0,      5,   0,    0,     0.06f},  // SOFT_PAD
    {PULSE,  0.25f,  1.0f, 0.75f, 0.5f,  200, 1.2f, 0,    0.1f, 0.6f, LOW, 0,       0.45f, 1, 0, 0,      0.003f, 4.8f, 0,   0,     0.07f},  // REEDS, a section's held chord
    {PULSE,  0.002f, 0.2f, 0.0f, 0.06f,  400, 1.5f, 1600, 0.07f, 1.1f, LOW, 0,      0.3f, 1, 0, 0,       0,      5,   0,    0,     0.10f},  // ARP
    {PULSE,  0.005f, 0.35f, 0.45f, 0.06f, 120, 1.2f, 700, 0.09f, 1.3f, LOW, 0,      0.5f, 1, 0, 0,       0,      5,   0,    0,     0.20f},  // SYNTH_BASS
    {PULSE,  0.04f,  0.5f, 0.7f, 0.35f,  350, 2.0f, 300,  0.2f, 0.9f, LOW, 0,       0.35f, 1, 0, 0,      0.006f, 5.5f, 0,   0,     0.11f},  // LEAD
    {KICK,   0.001f, 0.22f, 0.0f, 0.02f, 0, 0, 0,         0.04f, 0.7f, LOW, 0,      0.5f, 1, 0, 0,       0,      5,   0,    0,     0.35f},  // KICK_DRUM
    {NOISE,  0.001f, 0.035f, 0.0f, 0.02f, 6500, 0, 0,     0.1f, 0.7f, HIGH, 0,      0.5f, 1, 0, 0,       0,      5,   0,    0,     0.045f}, // HAT
    {NOISE,  0.001f, 0.03f, 0.0f, 0.02f, 1700, 0, 0,      0.1f, 4.0f, BAND, 0,      0.5f, 1, 0, 0,       0,      5,   0,    0,     0.25f},  // RIM
    {FM,     0.003f, 3.5f, 0.0f, 1.0f,   0, 0, 0,         0.8f, 0.7f, LOW, 0,       0.5f, 3.5f, 1.8f, 0.05f, 0,  5,   0,    0,     0.14f},  // BELL
    {FM,     0.6f,   2.0f, 0.9f, 1.2f,   0, 0, 0,         1.0f, 0.7f, LOW, 0,       0.5f, 1, 0.8f, 1,    0,      5,   0,    0,     0.22f},  // SUB, with some overtones for small speakers
    {FM,     1.4f,   3.0f, 0.7f, 2.5f,   0, 0, 0,         3.0f, 0.7f, LOW, 0,       0.5f, 2, 0.7f, 0.5f, 0,      5,   0,    0,     0.06f},  // GLASS
    {SINE,   1.2f,   3.0f, 0.4f, 2.5f,   0, 0, 0,         0.1f, 0.7f, LOW, 0,       0.5f, 1, 0, 0,       0.002f, 3,   0,    0,     0.035f}, // SHIMMER
    {FM,     0.002f, 0.7f, 0.0f, 0.3f,   0, 0, 0,         0.15f, 0.7f, LOW, 0,      0.5f, 2, 1.2f, 0,    0,      5,   0,    0,     0.13f},  // PLUCK
};

const int kModes[5][7] = {
    {0, 2, 4, 5, 7, 9, 11},  // major
    {0, 2, 4, 6, 7, 9, 11},  // lydian
    {0, 2, 4, 5, 7, 9, 10},  // mixolydian
    {0, 2, 3, 5, 7, 9, 10},  // dorian
    {0, 2, 3, 5, 7, 8, 10},  // minor
};
/** Two modes a season picks from: spring bright, summer major, autumn and winter darker. */
const int kSeasonModes[4][2] = {{0, 1}, {0, 2}, {3, 2}, {4, 3}};
/** Tonics a season picks from, as MIDI notes; winter's are lower. */
const int kSeasonRoots[4][4] = {{50, 52, 53, 55}, {53, 55, 57, 50}, {50, 52, 55, 57}, {45, 47, 48, 50}};
const float kSeasonTempo[4] = {1.0f, 1.03f, 0.94f, 0.86f};
const float kSeasonDensity[4] = {0.8f, 1.0f, 0.75f, 0.5f};
const float kStyleTempo[musicStyle::COUNT] = {84, 96, 88, 98, 64, 70};

/** Chords as scale degrees, 0 the tonic. */
const int kProgressions[][4] = {
    {0, 3, 4, 0}, {0, 5, 3, 4}, {0, 3, 0, 4}, {5, 3, 0, 4}, {0, 1, 4, 0}, {0, 5, 1, 4}, {0, 2, 3, 4},
    {0, 4, 5, 3}, {0, 3, 5, 4}, {3, 0, 4, 0}, {0, 6, 3, 0}, {1, 4, 0, 5}, {0, 3, 1, 4}, {0, 5, 2, 3},
};
/** Slow two-chord swings and drifts, for the long pads. */
const int kVamps[][4] = {{0, 3, 0, 3}, {0, 5, 0, 5}, {3, 0, 3, 4}, {0, 5, 3, 0}, {5, 3, 0, 0}, {0, 2, 5, 3}, {0, 3, 5, 3}};
constexpr int kProgressionCount = sizeof(kProgressions) / sizeof(kProgressions[0]);
constexpr int kVampCount = sizeof(kVamps) / sizeof(kVamps[0]);

/** Piece shapes by section: 0, 1, 2 are tunes A, B, C; -1 ends. An intro and an outro go round each. */
const int kForms[][6] = {{0, 0, 1, 0, -1}, {0, 1, 0, 1, -1}, {0, 0, 1, 1, 0, -1}, {0, 1, 2, 0, -1}, {0, 1, 0, -1}, {0, 1, 1, 0, -1}};
constexpr int kFormCount = sizeof(kForms) / sizeof(kForms[0]);

/** Rhythm cells for a melody, one beat (or more) each. */
enum Cell { QUARTER, EIGHTHS, DOTTED, SYNCOPATED, HALF, REST, OFFBEAT, WHOLE, CELLS };
/** How likely each cell is, by style. */
const float kCellWeights[musicStyle::COUNT][CELLS] = {
    {3, 3, 2, 3, 1, 1, 1, 0},  // parlour
    {3, 4, 0, 0, 2, 2, 2, 0},  // swing
    {3, 2, 1, 1, 2, 2, 2, 1},  // sixties
    {3, 3, 1, 1, 2, 2, 2, 1},  // synth pop
    {1, 0, 0, 0, 3, 4, 1, 2},  // ambient
    {2, 1, 0, 0, 3, 3, 2, 1},  // future
};

constexpr int kControl = 32;

uint32_t hash(uint32_t a, uint32_t b) {
    uint32_t h = a * 0x9E3779B1u ^ (b + 0x7F4A7C15u + (a << 6) + (a >> 2));
    h ^= h >> 16; h *= 0x85EBCA6Bu;
    h ^= h >> 13; h *= 0xC2B2AE35u;
    h ^= h >> 16;
    return h ? h : 1;
}

float unit(uint32_t h) { return (h >> 8) * (1.0f / 16777216.0f); }

/** sin(2 pi phase) for phase 0..1, to about 0.1%. */
inline float fastSin(float phase) {
    float x = phase - 0.5f;
    float y = 8.0f * x - 16.0f * x * std::fabs(x);
    y = 0.225f * (y * std::fabs(y) - y) + y;
    return -y;
}

/** Into 0..1. A truncating cast, since floor is a library call on some desktop builds. */
inline float wrap(float p) {
    float w = p - static_cast<float>(static_cast<int>(p));
    return w < 0.0f ? w + 1.0f : w;
}

/** A phase moved on by less than a cycle. */
inline float advance(float phase, float dt) {
    phase += dt;
    return phase >= 1.0f ? phase - 1.0f : phase;
}

inline float sawBlep(float& phase, float dt) {
    phase = advance(phase, dt);
    return 2.0f * phase - 1.0f - polyBlep(phase, dt);
}

inline float pulseBlep(float& phase, float dt, float width) {
    phase = advance(phase, dt);
    float v = phase < width ? 1.0f : -1.0f;
    v += polyBlep(phase, dt);
    v -= polyBlep(wrap(phase - width), dt);
    return v;
}

static_assert(INSTRUMENTS <= Music::kInstruments, "room for every instrument's coefficients");

/** Overall music level at full volume, by style, so each sits near -25 dB RMS. */
const float kLevel[musicStyle::COUNT] = {0.95f, 0.53f, 0.53f, 0.63f, 0.84f, 0.84f};

}  // namespace

int musicStyleOf(int year) {
    if (year < 1918) return musicStyle::PARLOUR;
    if (year < 1952) return musicStyle::SWING;
    if (year < 1978) return musicStyle::SIXTIES;
    if (year < 2000) return musicStyle::SYNTH_POP;
    if (year < 2040) return musicStyle::AMBIENT;
    return musicStyle::FUTURE;
}

Music::Music(float sampleRate) : sampleRate_(sampleRate) {
    fade_.value = 1.0f;
    tune();
}

void Music::tune() {
    const float sampleRate = sampleRate_;
    fade_.setTime(0.6f, sampleRate);
    for (OnePole& a : air_) a.set(6000.0f, sampleRate);
    for (int i = 0; i < INSTRUMENTS; ++i) {
        const Patch& p = kPatches[i];
        coeffs_[i].attack = 1.0f / std::max(1.0f, p.attack * sampleRate);
        coeffs_[i].decay = std::exp(-1.0f / (p.decay * sampleRate));
        // A third of the release as the time constant, so a pad is gone a few releases after it lets go.
        coeffs_[i].release = std::exp(-3.0f / (p.release * sampleRate));
        coeffs_[i].fenv32 = std::exp(-kControl / (p.envTime * sampleRate));
        coeffs_[i].pitch = std::exp(-1.0f / (p.envTime * sampleRate));
    }
}

void Music::setSampleRate(float sampleRate) {
    sampleRate_ = sampleRate;
    tune();
    for (OnePole& a : air_) a.y = 0.0f;
    silence();
    // The next piece starts straight away, at the new rate.
    state_ = WAITING;
    startAt_ = now_;
}

void Music::silence() {
    for (Voice& v : voices_) v.on = false;
    pendingCount_ = 0;
    nextDue_ = INT64_MAX;
    fade_.value = 0.0f;
}

void Music::idle() {
    if (state_ == PLAYING && yearIn_.load(std::memory_order_acquire) <= 0) {
        silence();
        stopping_ = true;
        endPiece(2.0f);
    }
}

void Music::set(int year, int season, uint32_t seed) {
    seasonIn_.store(season & 3, std::memory_order_relaxed);
    seedIn_.store(seed, std::memory_order_relaxed);
    yearIn_.store(year, std::memory_order_release);
}

int Music::degreeMidi(int degree, int base) const {
    int octave = degree >= 0 ? degree / 7 : -((6 - degree) / 7);
    return base + 12 * octave + mode_[degree - 7 * octave];
}

/** [degree]'s note in the octave nearest [centre]. */
int Music::nearest(int degree, int centre) const {
    int m = degreeMidi(((degree % 7) + 7) % 7, root_);
    while (m < centre - 6) m += 12;
    while (m >= centre + 6) m -= 12;
    return m;
}

/** [degree] moved to the nearest note of [chord]'s triad. */
int Music::chordTone(int chord, int degree) const {
    int best = degree, bestDistance = 99;
    for (int d = degree - 3; d <= degree + 3; ++d) {
        int rel = (((d - chord) % 7) + 7) % 7;
        if (rel != 0 && rel != 2 && rel != 4) continue;
        int distance = std::abs(d - degree);
        if (distance < bestDistance) { best = d; bestDistance = distance; }
    }
    return best;
}

int Music::chordAt(int s, int b) const {
    const Section& sec = sections_[s];
    bool slow = style_ >= musicStyle::SYNTH_POP;
    if (sec.motif < 0 && s > 0) {
        // The outro: to the subdominant and home.
        const int slowEnd[4] = {3, 3, 0, 0};
        const int fastEnd[4] = {3, 4, 0, 0};
        return (slow ? slowEnd : fastEnd)[b & 3];
    }
    const int* p = slow ? kVamps[progressions_[sec.progression] % kVampCount] : kProgressions[progressions_[sec.progression] % kProgressionCount];
    if (slow) return p[(b / 2) & 3];
    // Eight one-bar chords: the progression, then again closing on the dominant and the tonic.
    if (b < 4) return p[b];
    const int close[4] = {p[0], p[1], 4, 0};
    return close[b - 4];
}

void Music::makeMotif(Motif& m, uint32_t seed) {
    // A motif of all rests says nothing, so try again.
    for (int tries = 0; tries < 8; ++tries) {
        makeMotifOnce(m, hash(seed, static_cast<uint32_t>(tries)));
        if (m.count >= 3) return;
    }
}

void Music::makeMotifOnce(Motif& m, uint32_t seed) {
    Rng r;
    r.seed(seed);
    m.count = 0;
    const float* w = kCellWeights[style_];
    float weights[CELLS];
    float total = 0;
    for (int c = 0; c < CELLS; ++c) {
        weights[c] = w[c];
        if (c == REST) weights[c] += (1.0f - density_) * 6.0f;
        if (c == EIGHTHS || c == SYNCOPATED) weights[c] *= density_;
        total += weights[c];
    }
    int degree = 7 + 2 * static_cast<int>(r.uniform() * 3);  // a tonic triad note an octave up
    int beat = 0;
    while (beat < 8 && m.count < 14) {
        float pick = r.uniform() * total;
        int cell = 0;
        while (cell < CELLS - 1 && pick >= weights[cell]) { pick -= weights[cell]; ++cell; }
        int beats = cell == HALF ? 2 : cell == WHOLE ? 4 : 1;
        if (beat + beats > 8) continue;
        // Notes in the cell: (start, length) in 16ths.
        int starts[3], lengths[3], n = 0;
        switch (cell) {
            case QUARTER: starts[0] = 0; lengths[0] = 4; n = 1; break;
            case EIGHTHS: starts[0] = 0; lengths[0] = 2; starts[1] = 2; lengths[1] = 2; n = 2; break;
            case DOTTED: starts[0] = 0; lengths[0] = 3; starts[1] = 3; lengths[1] = 1; n = 2; break;
            case SYNCOPATED: starts[0] = 0; lengths[0] = 1; starts[1] = 1; lengths[1] = 2; starts[2] = 3; lengths[2] = 1; n = 3; break;
            case HALF: starts[0] = 0; lengths[0] = 8; n = 1; break;
            case OFFBEAT: starts[0] = 2; lengths[0] = 2; n = 1; break;
            case WHOLE: starts[0] = 0; lengths[0] = 16; n = 1; break;
            default: n = 0; break;
        }
        for (int i = 0; i < n && m.count < 16; ++i) {
            m.step[m.count] = static_cast<uint8_t>(beat * 4 + starts[i]);
            m.length[m.count] = static_cast<uint8_t>(lengths[i]);
            m.degree[m.count] = static_cast<int8_t>(degree);
            ++m.count;
            // Mostly steps, now and then a leap, kept to about an octave and a half.
            float s = r.uniform();
            int move = s < 0.3f ? -1 : s < 0.6f ? 1 : s < 0.7f ? 0 : s < 0.8f ? -2 : s < 0.9f ? 2 : s < 0.95f ? -3 : 4;
            degree += move;
            if (degree < 3) degree = 3 + (3 - degree);
            if (degree > 13) degree = 13 - (degree - 13);
        }
        beat += beats;
    }
}

void Music::beginPiece(int year, int season) {
    pieceSeed_ = hash(seedIn_.load(std::memory_order_relaxed), static_cast<uint32_t>(piece_));
    rng_.seed(pieceSeed_);
    style_ = musicStyleOf(year);
    season_ = season;
    root_ = kSeasonRoots[season][rng_.next() % 4];
    mode_ = kModes[kSeasonModes[season][rng_.next() % 2]];
    bpm_ = kStyleTempo[style_] * (0.94f + 0.12f * rng_.uniform()) * kSeasonTempo[season];
    swing_ = style_ == musicStyle::SWING ? 0.8f : 0.0f;
    density_ = kSeasonDensity[season];
    drums_ = season != musicSeason::WINTER &&
             (style_ == musicStyle::SWING || style_ == musicStyle::SIXTIES || style_ == musicStyle::SYNTH_POP || style_ == musicStyle::FUTURE);
    stepSamples_ = static_cast<int>(60.0f / bpm_ / 4.0f * sampleRate_);
    barSamples_ = stepSamples_ * 16;
    bool slow = style_ >= musicStyle::SYNTH_POP;
    for (int k = 0; k < 3; ++k) {
        int count = slow ? kVampCount : kProgressionCount;
        int p;
        do p = static_cast<int>(rng_.next() % count); while ((k > 0 && p == progressions_[0]) || (k > 1 && p == progressions_[1]));
        progressions_[k] = p;
        makeMotif(motifs_[k], hash(pieceSeed_, 100 + k));
    }
    const int* form = kForms[rng_.next() % kFormCount];
    sectionCount_ = 0;
    sections_[sectionCount_++] = Section{4, 0, -1, 0, false};
    int seen[3] = {};
    for (int i = 0; form[i] >= 0; ++i) {
        int tune = form[i];
        sections_[sectionCount_++] = Section{8, tune, tune, seen[tune], seen[tune] > 0 || tune > 0};
        ++seen[tune];
    }
    sections_[sectionCount_++] = Section{4, 0, -1, 0, false};
    totalBars_ = 0;
    for (int s = 0; s < sectionCount_; ++s) totalBars_ += sections_[s].bars;
    bar_ = 0;
    section_ = 0;
    barInSection_ = 0;
    lastBass_ = 0;
    nextBarAt_ = now_ + stepSamples_;
    fade_.value = 1.0f;
    stopping_ = false;
    state_ = PLAYING;
    ++piece_;
}

void Music::endPiece(float gapSeconds) {
    state_ = WAITING;
    startAt_ = now_ + static_cast<int64_t>(gapSeconds * sampleRate_);
}

void Music::add(int part, int step, int instrument, int midi, float velocity, float lengthSteps, float pan) {
    if (pendingCount_ >= kPending) return;
    Pending& p = pending_[pendingCount_++];
    float offset = static_cast<float>(step * stepSamples_);
    if ((step & 3) == 2) offset += swing_ * stepSamples_ * (2.0f / 3.0f);
    offset += rng_.uniform() * 0.006f * sampleRate_;  // a few milliseconds late, by hand
    p.at = nextBarAt_ + static_cast<int64_t>(offset);
    p.instrument = instrument;
    p.midi = midi;
    p.velocity = velocity * (0.9f + 0.2f * rng_.uniform());
    p.pan = pan;
    p.length = std::max(1, static_cast<int>(lengthSteps * stepSamples_ * 0.92f));
    if (p.at < nextDue_) nextDue_ = p.at;
    if (noteHook) {
        MusicNote n;
        n.piece = piece_;
        n.style = style_;
        n.season = season_;
        n.bar = bar_;
        n.step = step;
        n.part = part;
        n.instrument = instrument;
        n.midi = midi;
        n.velocity = p.velocity;
        noteHook(hookUser, n);
    }
}

void Music::composeBar() {
    using namespace musicStyle;
    const Section& sec = sections_[section_];
    const int b = barInSection_;
    const int chord = chordAt(section_, b);
    int nextChord;
    if (b + 1 < sec.bars) nextChord = chordAt(section_, b + 1);
    else if (section_ + 1 < sectionCount_) nextChord = chordAt(section_ + 1, 0);
    else nextChord = 0;
    const bool last = bar_ == totalBars_ - 1;
    const bool body = sec.motif >= 0;
    const bool winter = season_ == musicSeason::WINTER;
    const bool drums = drums_ && body;
    const bool newChord = style_ >= SYNTH_POP ? (b % 2 == 0) : true;
    const int centre = root_ + 7;
    // Not below about 65 Hz, which small speakers lose.
    const int bassCentre = std::max(root_ - 10, 42);
    const int bass = nearest(chord, bassCentre);
    const int fifth = nearest(chord + 4, bassCentre + 5);

    // Chord notes: triads, sevenths for jazz and the sixties, an added ninth for ambient, fourths for the future.
    int tones[4];
    int toneCount = 3;
    for (int i = 0; i < 3; ++i) tones[i] = nearest(chord + (style_ == FUTURE ? 3 : 2) * i, centre);
    if (style_ == SWING || style_ == SIXTIES) tones[toneCount++] = nearest(chord + 6, centre);
    if (style_ == AMBIENT) tones[toneCount++] = nearest(chord + 1, centre + 7);
    if (style_ == SYNTH_POP) tones[toneCount++] = nearest(chord, centre + 12);

    if (last) {
        // The last bar: one long chord to ring out.
        int inst = style_ == PARLOUR ? PIANO : style_ == SWING ? REEDS : style_ == SIXTIES ? EPIANO : style_ == SYNTH_POP ? PAD : style_ == AMBIENT ? SOFT_PAD : GLASS;
        int bassInst = style_ == PARLOUR ? PIANO : style_ == SWING ? UPRIGHT : style_ == SIXTIES ? BASS_GUITAR : style_ == SYNTH_POP ? SYNTH_BASS : SUB;
        for (int i = 0; i < toneCount; ++i) add(musicPart::CHORD, 0, inst, tones[i], 0.55f, 14, 0.25f * (i - 1.5f));
        add(musicPart::BASS, 0, bassInst, bass, 0.7f, 14, -0.1f);
        return;
    }

    switch (style_) {
        case PARLOUR:
            if (winter) {
                add(musicPart::BASS, 0, PIANO, bass, 0.75f, 7, -0.15f);
                for (int i = 0; i < 3; ++i) add(musicPart::CHORD, 4, PIANO, tones[i], 0.4f, 4, 0.15f);
            } else {
                add(musicPart::BASS, 0, PIANO, bass, 0.8f, 3, -0.15f);
                add(musicPart::BASS, 8, PIANO, fifth, 0.7f, 3, -0.15f);
                for (int s : {4, 12}) for (int i = 0; i < 3; ++i) add(musicPart::CHORD, s, PIANO, tones[i], 0.45f, 2, 0.15f);
            }
            if (sec.counter && !winter && b % 2 == 0) add(musicPart::COUNTER, 0, CORNET, nearest(chord + 2, root_ + 4), 0.5f, 28, -0.3f);
            break;
        case SWING: {
            if (winter) {
                add(musicPart::BASS, 0, UPRIGHT, bass, 0.8f, 7, -0.1f);
                add(musicPart::BASS, 8, UPRIGHT, fifth, 0.7f, 7, -0.1f);
            } else {
                // Walking: root, a chord note, the fifth, then a half step into the next root.
                int next = nearest(nextChord, bassCentre);
                int approach = next + ((rng_.next() & 1) ? 1 : -1);
                add(musicPart::BASS, 0, UPRIGHT, bass, 0.8f, 4, -0.1f);
                add(musicPart::BASS, 4, UPRIGHT, nearest(chord + 2, bassCentre + 3), 0.65f, 4, -0.1f);
                add(musicPart::BASS, 8, UPRIGHT, fifth, 0.7f, 4, -0.1f);
                add(musicPart::BASS, 12, UPRIGHT, approach, 0.65f, 4, -0.1f);
            }
            if (winter || sec.variant % 2 == 0) {
                for (int i = 0; i < toneCount; ++i) add(musicPart::CHORD, 0, REEDS, tones[i], 0.5f, 15, 0.3f * (i - 1.5f));
            } else {
                for (int i = 0; i < toneCount; ++i) {
                    add(musicPart::CHORD, 0, VIBES, tones[i], 0.35f, 3, 0.3f);
                    add(musicPart::CHORD, 6, VIBES, tones[i], 0.3f, 2, 0.3f);
                }
            }
            if (drums) {
                add(musicPart::DRUMS, 4, BRUSH, 60, 0.7f, 2, 0.2f);
                add(musicPart::DRUMS, 12, BRUSH, 60, 0.7f, 2, 0.2f);
                for (int s : {0, 6, 8, 14}) add(musicPart::DRUMS, s, HAT, 80, s % 8 ? 0.3f : 0.45f, 1, 0.35f);
            }
            break;
        }
        case SIXTIES:
            if (winter) {
                add(musicPart::BASS, 0, BASS_GUITAR, bass, 0.8f, 6, -0.1f);
                add(musicPart::BASS, 8, BASS_GUITAR, fifth, 0.7f, 6, -0.1f);
                for (int i = 0; i < toneCount; ++i) add(musicPart::CHORD, 0, EPIANO, tones[i], 0.45f, 12, 0.2f);
            } else {
                add(musicPart::BASS, 0, BASS_GUITAR, bass, 0.8f, 3, -0.1f);
                add(musicPart::BASS, 6, BASS_GUITAR, fifth, 0.6f, 2, -0.1f);
                add(musicPart::BASS, 8, BASS_GUITAR, fifth, 0.7f, 3, -0.1f);
                add(musicPart::BASS, 14, BASS_GUITAR, nearest(nextChord, bassCentre), 0.6f, 2, -0.1f);
                const int comp[3] = {0, 6, 10};
                const float compLength[3] = {4, 2, 3};
                for (int k = 0; k < 3; ++k)
                    for (int i = 0; i < toneCount; ++i) add(musicPart::CHORD, comp[k], EPIANO, tones[i], k ? 0.35f : 0.45f, compLength[k], 0.2f);
            }
            if (drums) {
                for (int s = 0; s < 16; s += 2) add(musicPart::DRUMS, s, HAT, 80, (s & 3) ? 0.35f : 0.22f, 1, 0.3f);
                add(musicPart::DRUMS, 3, RIM, 70, 0.5f, 1, -0.2f);
                add(musicPart::DRUMS, 10, RIM, 70, 0.5f, 1, -0.2f);
                add(musicPart::DRUMS, 0, KICK_DRUM, 36, 0.3f, 2, 0);
                add(musicPart::DRUMS, 8, KICK_DRUM, 36, 0.3f, 2, 0);
            }
            break;
        case SYNTH_POP:
            if (winter) {
                add(musicPart::BASS, 0, SYNTH_BASS, bass, 0.7f, 6, 0);
                add(musicPart::BASS, 8, SYNTH_BASS, bass, 0.6f, 6, 0);
            } else {
                for (int s = 0; s < 16; s += 2) add(musicPart::BASS, s, SYNTH_BASS, (s == 6 || s == 14) ? bass + 12 : bass, s % 4 ? 0.5f : 0.65f, 1.5f, 0);
            }
            if (newChord) for (int i = 0; i < toneCount; ++i) add(musicPart::CHORD, 0, PAD, tones[i], 0.6f, 31, 0.3f * (i - 1.5f));
            if (sec.counter || season_ == musicSeason::SUMMER) {
                int every = winter ? 2 : 1;
                for (int s = 0; s < 16; s += every) {
                    int k = s % 4;
                    int up = (b % 2) ? 12 : 0;
                    add(musicPart::COUNTER, s, ARP, tones[k % toneCount] + up, (s & 3) ? 0.35f : 0.5f, 0.8f, (s & 4) ? 0.4f : -0.4f);
                }
            }
            if (drums) {
                add(musicPart::DRUMS, 0, KICK_DRUM, 36, 0.55f, 2, 0);
                add(musicPart::DRUMS, 8, KICK_DRUM, 36, 0.55f, 2, 0);
                add(musicPart::DRUMS, 4, RIM, 70, 0.35f, 1, 0.1f);
                add(musicPart::DRUMS, 12, RIM, 70, 0.35f, 1, 0.1f);
                for (int s : {2, 6, 10, 14}) add(musicPart::DRUMS, s, HAT, 80, 0.35f, 1, 0.25f);
            }
            break;
        case AMBIENT:
            if (newChord) {
                for (int i = 0; i < toneCount; ++i) add(musicPart::CHORD, 0, SOFT_PAD, tones[i], 0.6f, 31, 0.35f * (i - 1.5f));
                add(musicPart::BASS, 0, SUB, bass, 0.6f, 31, 0);
                if (rng_.uniform() < 0.5f * density_ + 0.2f) add(musicPart::COUNTER, 4, SHIMMER, nearest(chord + 4, root_ + 26), 0.5f, 24, 0.4f);
            }
            break;
        default:  // FUTURE
            if (newChord) {
                for (int i = 0; i < toneCount; ++i) add(musicPart::CHORD, 0, GLASS, tones[i], 0.6f, 31, 0.4f * (i - 1));
                add(musicPart::BASS, 0, SUB, bass, 0.6f, 31, 0);
            }
            if (body) {
                for (int s = 0; s < 16; s += 2) {
                    if (rng_.uniform() > 0.55f * density_) continue;
                    int k = static_cast<int>(rng_.next() % toneCount);
                    add(musicPart::COUNTER, s, PLUCK, tones[k] + 12, 0.4f + 0.2f * rng_.uniform(), 2, (s & 2) ? 0.5f : -0.5f);
                }
            }
            if (drums) for (int s = 0; s < 16; s += 2) add(musicPart::DRUMS, s, HAT, 80, (s & 2) ? 0.22f : 0.12f, 1, 0.3f);
            break;
    }

    if (!body) return;
    // The melody: the section's motif over two bars, four times: as it is, moved, varied, then closing.
    const Motif& m = motifs_[sec.motif];
    const int phrase = b / 2;
    const int half = b % 2;
    int melodyInst;
    switch (style_) {
        case PARLOUR: melodyInst = PIANO; break;
        case SWING: melodyInst = (sec.motif + sec.variant) % 2 ? VIBES : CLARINET; break;
        case SIXTIES: melodyInst = sec.motif == 1 ? EPIANO : FLUTE; break;
        case SYNTH_POP: melodyInst = LEAD; break;
        case AMBIENT: melodyInst = BELL; break;
        default: melodyInst = sec.motif == 1 ? PLUCK : BELL; break;
    }
    const int melodyBase = root_ + 12;
    const uint32_t phraseSeed = hash(pieceSeed_, static_cast<uint32_t>(sec.motif * 1000 + sec.variant * 100 + phrase));
    const int shifts[5] = {1, -1, 2, 0, -2};
    int transpose = phrase == 1 ? shifts[phraseSeed % 5] : 0;
    // A tune coming round again may start from another note.
    if (phrase == 0 && sec.variant > 0 && (phraseSeed & 256)) transpose = shifts[(phraseSeed >> 9) % 5];
    if (phrase == 3 && half == 1) {
        // Close on the chord's root, near where the tune was.
        int near = m.count ? m.degree[m.count - 1] : 7;
        int home = chord + 7 * ((near - chord + 3) / 7);
        add(musicPart::MELODY, 0, melodyInst, degreeMidi(home, melodyBase - 12), 0.65f, 12, 0.1f);
        return;
    }
    for (int i = 0; i < m.count; ++i) {
        int step = m.step[i] - half * 16;
        if (step < 0 || step >= 16) continue;
        uint32_t h = hash(phraseSeed, static_cast<uint32_t>(i));
        // Sparser seasons leave notes out, never the phrase's first.
        if (i > 0 && unit(h) < (1.0f - density_) * 0.6f) continue;
        int degree = m.degree[i] + transpose;
        // The third phrase and every return of a tune change a few notes.
        // Sparse tunes change more, or they'd come back the same.
        float change = m.count < 6 ? 0.55f : 0.3f;
        if ((phrase == 2 || sec.variant > 0) && unit(hash(h, 7)) < change) degree += (h & 16) ? 1 : -1;
        if ((step & 7) == 0) degree = chordTone(chord, degree);
        add(musicPart::MELODY, step, melodyInst, degreeMidi(degree, melodyBase - 12), 0.65f, m.length[i], 0.1f);
    }
}

void Music::control() {
    int year = yearIn_.load(std::memory_order_acquire);
    if (state_ == WAITING) {
        if (year > 0 && now_ >= startAt_) beginPiece(year, seasonIn_.load(std::memory_order_relaxed));
        return;
    }
    if (year <= 0 && !stopping_) stopping_ = true;
    if (stopping_) {
        if (fade_.value < 1e-3f) {
            silence();
            endPiece(2.0f);
        }
        return;
    }
    while (bar_ < totalBars_ && now_ + barSamples_ >= nextBarAt_) {
        composeBar();
        nextBarAt_ += barSamples_;
        ++bar_;
        if (++barInSection_ >= sections_[section_].bars) { barInSection_ = 0; ++section_; }
        if (bar_ == totalBars_) endAt_ = nextBarAt_ + static_cast<int64_t>(5.0f * sampleRate_);
    }
    if (bar_ >= totalBars_ && now_ >= endAt_) {
        float gap = 18.0f + 25.0f * rng_.uniform() + (season_ == musicSeason::WINTER ? 15.0f : 0.0f);
        endPiece(gap);
    }
}

void Music::start(const Pending& p) {
    Voice* best = nullptr;
    for (Voice& v : voices_) {
        if (!v.on) { best = &v; break; }
        // Otherwise the quietest, a released one first.
        if (!best || (v.releasing && !best->releasing) || (v.releasing == best->releasing && v.env < best->env)) best = &v;
    }
    Voice& v = *best;
    v = Voice();
    v.on = true;
    v.instrument = p.instrument;
    v.freq = 440.0f * std::pow(2.0f, (p.midi - 69) / 12.0f);
    v.velocity = p.velocity;
    panGains(p.pan, v.panLeft, v.panRight);
    v.gate = p.length;
    v.fenv = 1.0f;
    v.pitchEnv = 1.0f;
    v.rng.seed(static_cast<uint32_t>(p.at) * 2654435761u + static_cast<uint32_t>(p.midi));
    const Patch& patch = kPatches[p.instrument];
    if (patch.wave == SAWS || patch.wave == PULSE || patch.wave == NOISE)
        v.filter.set(patch.cutoff + patch.keytrack * v.freq + patch.envAmount, patch.q, sampleRate_);
    else if (patch.breath > 0)
        v.filter.set(v.freq * 2.0f, patch.q, sampleRate_);
}

float Music::render(Voice& v, bool tick) {
    const Patch& p = kPatches[v.instrument];
    const Coeffs& c = coeffs_[v.instrument];
    if (tick) {
        v.fenv *= c.fenv32;
        v.lfo = advance(v.lfo, kControl * p.lfoRate / sampleRate_);
        float s = fastSin(v.lfo);
        v.pitch = 1.0f + p.vibrato * s;
        v.tremolo = 1.0f - p.tremolo * (0.5f + 0.5f * s);
        if (p.wave == SAWS || p.wave == PULSE || p.wave == NOISE)
            v.filter.set(p.cutoff + p.keytrack * v.freq + p.envAmount * v.fenv, p.q, sampleRate_);
    }
    if (v.gate > 0 && --v.gate == 0) { v.stage = 2; v.releasing = true; }
    switch (v.stage) {
        case 0:
            v.env += c.attack;
            if (v.env >= 1.0f) { v.env = 1.0f; v.stage = 1; }
            break;
        case 1: v.env = p.sustain + (v.env - p.sustain) * c.decay; break;
        default: v.env *= c.release; break;
    }
    if ((v.stage == 2 || (v.stage == 1 && p.sustain == 0)) && v.env < 1e-3f) { v.on = false; return 0; }

    float dt = v.freq * v.pitch / sampleRate_;
    float x;
    switch (p.wave) {
        case SINE:
            v.phase[0] = advance(v.phase[0], dt);
            x = fastSin(v.phase[0]);
            if (p.breath > 0) { v.filter.process(v.rng.white()); x += p.breath * v.filter.band * 4.0f; }
            break;
        case FM: {
            v.phase[1] = advance(v.phase[1], dt * p.ratio);
            float index = p.index * (p.indexFloor + (1.0f - p.indexFloor) * v.fenv);
            v.phase[0] = advance(v.phase[0], dt);
            x = fastSin(wrap(v.phase[0] + index * fastSin(v.phase[1]) * (1.0f / kTwoPi)));
            break;
        }
        case SAWS:
            x = 0.5f * (sawBlep(v.phase[0], dt * (1.0f + p.detune)) + sawBlep(v.phase[1], dt * (1.0f - p.detune)));
            v.filter.process(x);
            x = v.filter.low;
            break;
        case PULSE:
            x = pulseBlep(v.phase[0], dt, p.width);
            v.filter.process(x);
            x = v.filter.low;
            break;
        case NOISE:
            v.filter.process(v.rng.white());
            x = p.filterMode == HIGH ? v.filter.high : v.filter.band;
            break;
        default:  // KICK: a sine falling from a few times its pitch
            v.pitchEnv *= c.pitch;
            v.phase[0] = advance(v.phase[0], dt * (1.0f + 2.5f * v.pitchEnv));
            x = fastSin(v.phase[0]);
            break;
    }
    return x * v.env * v.velocity * p.level * v.tremolo;
}

void Music::next(float& left, float& right) {
    bool tick = false;
    if (--ctl_ <= 0) {
        ctl_ = kControl;
        tick = true;
        control();
    }
    if (now_ >= nextDue_) {
        int kept = 0;
        nextDue_ = INT64_MAX;
        for (int i = 0; i < pendingCount_; ++i) {
            if (pending_[i].at <= now_) start(pending_[i]);
            else {
                if (pending_[i].at < nextDue_) nextDue_ = pending_[i].at;
                pending_[kept++] = pending_[i];
            }
        }
        pendingCount_ = kept;
    }
    float l = 0, r = 0;
    for (Voice& v : voices_) {
        if (!v.on) continue;
        float x = render(v, tick);
        l += x * v.panLeft;
        r += x * v.panRight;
    }
    float g = fade_.next(stopping_ ? 0.0f : 1.0f) * kLevel[style_];
    left = flush(air_[0].next(l)) * g;
    right = flush(air_[1].next(r)) * g;
    ++now_;
}

}  // namespace interchange
