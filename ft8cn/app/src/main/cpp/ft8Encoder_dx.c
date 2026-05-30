//
// Created for FT8CN DX mode (Fox/Hound multistream support)
//

#include <jni.h>
#include <string.h>
#include <math.h>
#include "encode.h"
#include "ft8Encoder.h"
#include "../common/debug.h"

#define FT8_SYMBOL_BT 2.0f
#define FT4_SYMBOL_BT 1.0f
#define GFSK_CONST_K 5.336446f

void gfsk_pulse(int n_spsym, float symbol_bt, float *pulse) {
    for (int i = 0; i < 3 * n_spsym; ++i) {
        float t = i / (float) n_spsym - 1.5f;
        float arg1 = GFSK_CONST_K * symbol_bt * (t + 0.5f);
        float arg2 = GFSK_CONST_K * symbol_bt * (t - 0.5f);
        pulse[i] = (erff(arg1) - erff(arg2)) / 2;
    }
}

void synth_gfsk(const uint8_t *symbols, int n_sym, float f0, float symbol_bt,
                float symbol_period, int signal_rate, float *signal) {
    int n_spsym = (int)(0.5f + (float)signal_rate * symbol_period);
    int n_wave = n_sym * n_spsym;
    float hmod = 1.0f;
    float dphi_peak = 2 * M_PI * hmod / n_spsym;

    float *dphi = malloc(sizeof(float) * (n_wave + 2 * n_spsym));
    if (dphi == 0) return;

    for (int i = 0; i < n_wave + 2 * n_spsym; ++i) {
        dphi[i] = 2 * M_PI * f0 / signal_rate;
    }

    float *pulse = (float *) malloc(sizeof(float) * 3 * n_spsym);
    gfsk_pulse(n_spsym, symbol_bt, pulse);

    for (int i = 0; i < n_sym; ++i) {
        int ib = i * n_spsym;
        for (int j = 0; j < 3 * n_spsym; ++j) {
            dphi[j + ib] += dphi_peak * symbols[i] * pulse[j];
        }
    }

    for (int j = 0; j < 2 * n_spsym; ++j) {
        dphi[j] += dphi_peak * pulse[j + n_spsym] * symbols[0];
        dphi[j + n_sym * n_spsym] += dphi_peak * pulse[j] * symbols[n_sym - 1];
    }

    float phi = 0;
    for (int k = 0; k < n_wave; ++k) {
        signal[k] = sinf(phi);
        phi = fmodf(phi + dphi[k + n_spsym], 2 * M_PI);
    }

    int n_ramp = n_spsym / 8;
    for (int i = 0; i < n_ramp; ++i) {
        float env = (1 - cosf(2 * M_PI * i / (2 * n_ramp))) / 2;
        signal[i] *= env;
        signal[n_wave - 1 - i] *= env;
    }

    free(pulse);
    free(dphi);
}

// [NEW] Generate FT8 with Fox multi-call support
void generateFt8Dx(char *message, float frequency, short *buffer, bool is_fox_multi) {
    uint8_t packed[FTX_LDPC_K_BYTES];
    int rc = pack77(message, packed);
    if (rc < 0) return;

    float symbol_bt = FT8_SYMBOL_BT;
    uint8_t tones[FT8_NN];

    // [NEW] Pass is_fox_multi flag to encode function
    ft8_encode(packed, tones, is_fox_multi);
    // [END NEW]

    int num_samples = (int)(0.5f + FT8_NN * FT8_SYMBOL_PERIOD * FT8_SAMPLE_RATE);
    int num_silence = 20;
    float signal[Ft8num_samples];

    for (int i = 0; i < Ft8num_samples; i++) signal[i] = 0;

    synth_gfsk(tones, FT8_NN, frequency, symbol_bt, FT8_SYMBOL_PERIOD,
               FT8_SAMPLE_RATE, signal + num_silence);

    for (int i = 0; i < Ft8num_samples; i++) {
        float x = signal[i];
        if (x > 1.0) x = 1.0; else if (x < -1.0) x = -1.0;
        buffer[i] = (short)(0.5 + (x * 32767.0));
    }
}

// Standard generate function (for compatibility)
void generateFt8ToBuffer(char *message, float frequency, short *buffer) {
    generateFt8Dx(message, frequency, buffer, false);
}