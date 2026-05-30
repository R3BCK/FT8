//
// Created for FT8CN DX mode (Fox/Hound multistream support)
//

#include <jni.h>
#include <string.h>
#include <math.h>
#include "decode.h"
#include "ft8Decoder.h"
#include "../fft/kiss_fftr.h"
#include "../common/debug.h"

#define LOG_LEVEL LOG_INFO

static float hann_i(int i, int N) {
    float x = sinf((float) M_PI * i / N);
    return x * x;
}

void signalToFFT(decoder_t *decoder, float signal[], int sample_rate) {
    int nfft = kFreq_osr * (int)(sample_rate * FT8_SYMBOL_PERIOD);
    float fft_norm = 2.0f / nfft;
    float *window = (float *) malloc(nfft * sizeof(window[0]));
    for (int i = 0; i < nfft; ++i) window[i] = hann_i(i, nfft);

    float *last_frame = (float *) malloc(nfft * sizeof(last_frame[0]));
    size_t fft_work_size;
    kiss_fftr_alloc(nfft, 0, 0, &fft_work_size);
    void *fft_work = malloc(fft_work_size);
    kiss_fftr_cfg fft_cfg = kiss_fftr_alloc(nfft, 0, fft_work, &fft_work_size);

    free(fft_work);
    free(window);
    free(last_frame);
}

void *init_decoder(int64_t utcTime, int sample_rate, int num_samples, bool is_ft8) {
    decoder_t *decoder = malloc(sizeof(decoder_t));
    decoder->utcTime = utcTime;
    decoder->num_samples = num_samples;
    decoder->mon_cfg = (monitor_config_t) {
            .f_min = 100,
            .f_max = 3000,
            .sample_rate = sample_rate,
            .time_osr = kTime_osr,
            .freq_osr = kFreq_osr,
            .protocol = is_ft8 ? PROTO_FT8 : PROTO_FT4
    };
    decoder->kLDPC_iterations = fast_kLDPC_iterations;
    monitor_init(&decoder->mon, &decoder->mon_cfg);
    return decoder;
}

void delete_decoder(decoder_t *decoder) {
    monitor_free(&decoder->mon);
    free(decoder);
}

void decoder_monitor_press(float signal[], decoder_t *decoder) {
    for (int frame_pos = 0; frame_pos + decoder->mon.block_size <= decoder->num_samples;
         frame_pos += decoder->mon.block_size) {
        monitor_process(&decoder->mon, signal + frame_pos);
    }
}

int decoder_ft8_find_sync(decoder_t *decoder) {
    decoder->num_candidates = ft8_find_sync(&decoder->mon.wf, kMax_candidates,
                                            decoder->candidate_list, kMin_score);
    LOG(LOG_DEBUG, "ft8_find_sync finished. %d candidates\n", decoder->num_candidates);

    for (int i = 0; i < kMax_decoded_messages; ++i) {
        decoder->decoded_hashtable[i] = NULL;
    }
    return decoder->num_candidates;
}

ft8_message decoder_ft8_analysis(int idx, decoder_t *decoder) {
    ft8_message ft8Message;
    ft8Message.isValid = false;
    ft8Message.utcTime = decoder->utcTime;
    ft8Message.candidate = decoder->candidate_list[idx];

    if (ft8Message.candidate.score < kMin_score) return ft8Message;

    ft8Message.freq_hz = (ft8Message.candidate.freq_offset +
                          (float) ft8Message.candidate.freq_sub / decoder->mon.wf.freq_osr) /
                         decoder->mon.symbol_period;
    ft8Message.time_sec = ((ft8Message.candidate.time_offset +
                            (float) ft8Message.candidate.time_sub) * decoder->mon.symbol_period) /
                          decoder->mon.wf.time_osr;

    if (!ft8_decode(&decoder->mon.wf, &ft8Message.candidate,
                    &ft8Message.message, decoder->kLDPC_iterations, &ft8Message.status)) {
        if (ft8Message.status.ldpc_errors > 0) {
            LOG(LOG_DEBUG, "LDPC decode: %d errors\n", ft8Message.status.ldpc_errors);
        } else if (ft8Message.status.crc_calculated != ft8Message.status.crc_extracted) {
            LOG(LOG_DEBUG, "CRC mismatch!\n");
        } else if (ft8Message.status.unpack_status != 0) {
            LOG(LOG_DEBUG, "Error while unpacking!\n");
        }
        return ft8Message;
    }

    ft8Message.snr = ft8Message.candidate.snr;

    // [NEW] Обработка мульти-сообщений Fox
    if (ft8Message.message.is_fox_multi) {
        LOG(LOG_DEBUG, "Fox multi-call detected: %d calls\n",
            ft8Message.message.fox_calls_count);
        for (int i = 0; i < ft8Message.message.fox_calls_count; i++) {
            LOG(LOG_DEBUG, "  Call %d: %s\n", i, ft8Message.message.fox_calls[i]);
        }
    }
    // [END NEW]

    int idx_hash = ft8Message.message.hash % kMax_decoded_messages;
    bool found_empty_slot = false, found_duplicate = false;

    do {
        if (decoder->decoded_hashtable[idx_hash] == NULL) {
            found_empty_slot = true;
        } else if ((decoder->decoded_hashtable[idx_hash]->hash == ft8Message.message.hash) &&
                   (0 == strcmp(decoder->decoded_hashtable[idx_hash]->text, ft8Message.message.text))) {
            found_duplicate = true;
        } else {
            idx_hash = (idx_hash + 1) % kMax_decoded_messages;
        }
    } while (!found_empty_slot && !found_duplicate);

    if (found_empty_slot) {
        memcpy(&decoder->decoded[idx_hash], &ft8Message.message, sizeof(ft8Message.message));
        decoder->decoded_hashtable[idx_hash] = &decoder->decoded[idx_hash];
        ++decoder->num_decoded;
        ft8Message.isValid = true;
    }

    memcpy(decoder->a91, ft8Message.message.a91, FTX_LDPC_K_BYTES);
    return ft8Message;
}

void decoder_ft8_reset(decoder_t *decoder, long utcTime, int num_samples) {
    LOG(LOG_DEBUG, "Monitor is resetting...");
    decoder->mon.wf.num_blocks = 0;
    decoder->mon.max_mag = -120.0f;
    decoder->utcTime = utcTime;
    decoder->num_samples = num_samples;
}

void recode(int a174[], int a79[]) {
    int i174 = 0;
    for (int i79 = 0; i79 < 79; i79++) {
        if (i79 < 7) {
            a79[i79] = kFT8CostasPattern[i79];
        } else if (i79 >= 36 && i79 < 36 + 7) {
            a79[i79] = kFT8CostasPattern[i79 - 36];
        } else if (i79 >= 72) {
            a79[i79] = kFT8CostasPattern[i79 - 72];
        } else {
            int sym = (a174[i174 + 0] << 2) | (a174[i174 + 1] << 1) | (a174[i174 + 2] << 0);
            i174 += 3;
            int map[] = {0, 1, 3, 2, 5, 6, 4, 7};
            sym = map[sym];
            a79[i79] = sym;
        }
    }
}