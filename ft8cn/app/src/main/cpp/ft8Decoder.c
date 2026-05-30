//
// Created by jmsmf on 2022/4/24.
//

#include "ft8Decoder.h"
#include <errno.h>      // [ADDED] For errno error codes
#include <string.h>     // [ADDED] For memset()

#define LOG_LEVEL LOG_INFO
//decoder_t decoder;

// Hanning window (applicable in 95% of cases).
static float hann_i(int i, int N) {
    float x = sinf((float) M_PI * i / N);
    return x * x;
}

// Convert signal to FFT, subtract signal in decoder
void signalToFFT(decoder_t *decoder, float signal[], int sample_rate) {
    int nfft = kFreq_osr * (int) (sample_rate * FT8_SYMBOL_PERIOD); // nfft = number of samples per FSK symbol * frequency oversampling rate
    float fft_norm = 2.0f / nfft; // [UNCHANGED] FFT normalization factor
    float *window = (float *) malloc(
            nfft * sizeof(window[0])); // [UNCHANGED] Allocate window space

    // [ADDED] Check malloc result
    if (window == NULL) {
        LOG(LOG_ERROR, "signalToFFT: malloc window failed, errno=%d\n", errno);
        return; // [ADDED] Early return on allocation failure
    }

    for (int i = 0; i < nfft; ++i) {
        window[i] = hann_i(i, nfft); // [UNCHANGED] Use Hanning window
    }

    float *last_frame = (float *) malloc(nfft * sizeof(last_frame[0]));
    // [ADDED] Check malloc result
    if (last_frame == NULL) {
        LOG(LOG_ERROR, "signalToFFT: malloc last_frame failed, errno=%d\n", errno);
        free(window);
        return;
    }

    size_t fft_work_size;
    kiss_fftr_alloc(nfft, 0, 0, &fft_work_size);
    void *fft_work = malloc(fft_work_size);
    // [ADDED] Check malloc result
    if (fft_work == NULL) {
        LOG(LOG_ERROR, "signalToFFT: malloc fft_work failed, errno=%d\n", errno);
        free(window);
        free(last_frame);
        return;
    }

    kiss_fftr_cfg fft_cfg = kiss_fftr_alloc(nfft, 0, fft_work, &fft_work_size);
    // [ADDED] fft_cfg not used further, kept for compatibility

    free(fft_work);
    free(window);
    free(last_frame);
}

void *init_decoder(int64_t utcTime, int sample_rate, int num_samples, bool is_ft8) {
    decoder_t *decoder = malloc(sizeof(decoder_t));

    // [FIX] Check malloc result
    if (decoder == NULL) {
        LOG(LOG_ERROR, "init_decoder: malloc failed, errno=%d\n", errno);
        return NULL;
    }

    // [FIX] Initialize all fields to zero
    memset(decoder, 0, sizeof(decoder_t));

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

    // [FIX] monitor_init returns void, so just call it without comparison
    monitor_init(&decoder->mon, &decoder->mon_cfg); // [FIX] Removed "!= 0" check

    LOG(LOG_DEBUG, "Init decoder OK, address: %p\n", (void*)decoder);
    return decoder;
}

void delete_decoder(decoder_t *decoder) {
    // [FIX] Protect against NULL pointer
    if (decoder == NULL) {
        LOG(LOG_WARN, "delete_decoder: called with NULL pointer\n");
        return;
    }

    LOG(LOG_DEBUG, "Free decoder, address: %p\n", (void*)decoder);

    // [FIX] Check if monitor data is valid before calling monitor_free
    if (decoder->mon.wf.mag != NULL) {
        monitor_free(&decoder->mon);
    }

    free(decoder);
}

void decoder_monitor_press(float signal[], decoder_t *decoder) {
    // [FIX] Validate input parameters
    if (decoder == NULL) {
        LOG(LOG_ERROR, "decoder_monitor_press: decoder is NULL\n");
        return;
    }
    if (signal == NULL) {
        LOG(LOG_ERROR, "decoder_monitor_press: signal array is NULL\n");
        return;
    }
    if (decoder->mon.block_size <= 0) {
        LOG(LOG_ERROR, "decoder_monitor_press: invalid block_size=%d\n", decoder->mon.block_size);
        return;
    }

    for (int frame_pos = 0;
         frame_pos + decoder->mon.block_size <= decoder->num_samples;
         frame_pos += decoder->mon.block_size) {

        // [FIX] Additional check inside loop
        if (decoder->mon.wf.mag == NULL) {
            LOG(LOG_ERROR, "decoder_monitor_press: waterfall mag is NULL at frame_pos=%d\n", frame_pos);
            break;
        }

        monitor_process(&decoder->mon, signal + frame_pos);
    }
}

int decoder_ft8_find_sync(decoder_t *decoder) {
    // [FIX] Validate input structure
    if (decoder == NULL) {
        LOG(LOG_ERROR, "decoder_ft8_find_sync: decoder is NULL\n");
        return 0;
    }

    // [CRITICAL FIX] Check waterfall structure before calling external ft8_find_sync
    // This prevents SIGSEGV when wf or wf.mag is NULL/uninitialized
    if (&decoder->mon.wf == NULL) {
        LOG(LOG_ERROR, "decoder_ft8_find_sync: waterfall struct is NULL\n");
        return 0;
    }
    if (decoder->mon.wf.mag == NULL) {
        LOG(LOG_ERROR, "decoder_ft8_find_sync: waterfall mag array is NULL\n");
        return 0;
    }
    if (decoder->mon.wf.num_blocks <= 0) {
        LOG(LOG_WARN, "decoder_ft8_find_sync: no waterfall data (num_blocks=%d)\n",
            decoder->mon.wf.num_blocks);
        return 0;
    }
    // [CRITICAL FIX] Additional safety: check that freq_osr and time_osr are valid
    if (decoder->mon.wf.freq_osr <= 0 || decoder->mon.wf.time_osr <= 0) {
        LOG(LOG_ERROR, "decoder_ft8_find_sync: invalid oversampling config\n");
        return 0;
    }

    // Detect FT8 signal - now safe to call with validated data
    decoder->num_candidates = ft8_find_sync(&decoder->mon.wf, kMax_candidates,
                                            decoder->candidate_list, kMin_score);

    LOG(LOG_DEBUG, "ft8_find_sync finished. %d candidates\n", decoder->num_candidates);

    // Initialize hash table pointers
    for (int i = 0; i < kMax_decoded_messages; ++i) {
        decoder->decoded_hashtable[i] = NULL;
    }
    return decoder->num_candidates;
}

ft8_message decoder_ft8_analysis(int idx, decoder_t *decoder) {
    ft8_message ft8Message;
    // [FIX] Zero-initialize structure
    memset(&ft8Message, 0, sizeof(ft8Message));
    ft8Message.isValid = false;

    // [FIX] Validate input parameters
    if (decoder == NULL) {
        LOG(LOG_ERROR, "decoder_ft8_analysis: decoder is NULL\n");
        return ft8Message;
    }
    if (idx < 0 || idx >= kMax_candidates) {
        LOG(LOG_ERROR, "decoder_ft8_analysis: invalid candidate index idx=%d\n", idx);
        return ft8Message;
    }

    ft8Message.utcTime = decoder->utcTime;
    ft8Message.candidate = decoder->candidate_list[idx];

    if (ft8Message.candidate.score < kMin_score) {
        return ft8Message;
    }

    // [FIX] Protect against division by zero
    if (decoder->mon.wf.freq_osr <= 0 || decoder->mon.symbol_period <= 0) {
        LOG(LOG_ERROR, "decoder_ft8_analysis: invalid config for frequency calculation\n");
        return ft8Message;
    }

    ft8Message.freq_hz =
            (ft8Message.candidate.freq_offset +
             (float) ft8Message.candidate.freq_sub / decoder->mon.wf.freq_osr) /
            decoder->mon.symbol_period;
    ft8Message.time_sec =
            ((ft8Message.candidate.time_offset + (float) ft8Message.candidate.time_sub)
             * decoder->mon.symbol_period) / decoder->mon.wf.time_osr;

    // [FIX] Check waterfall data before decode
    if (decoder->mon.wf.mag == NULL) {
        LOG(LOG_ERROR, "decoder_ft8_analysis: waterfall data is NULL\n");
        return ft8Message;
    }

    if (!ft8_decode(&decoder->mon.wf, &ft8Message.candidate,
                    &ft8Message.message, decoder->kLDPC_iterations,
                    &ft8Message.status)) {
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
    ft8Message.isValid = true;

    // Hash table logic (unchanged)
    int idx_hash = ft8Message.message.hash % kMax_decoded_messages;
    bool found_empty_slot = false;
    bool found_duplicate = false;

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
    }

    memcpy(decoder->a91, ft8Message.message.a91, FTX_LDPC_K_BYTES);
    return ft8Message;
}

void decoder_ft8_reset(decoder_t *decoder, long utcTime, int num_samples) {
    // [FIX] Validate decoder
    if (decoder == NULL) {
        LOG(LOG_ERROR, "decoder_ft8_reset: decoder is NULL\n");
        return;
    }

    LOG(LOG_DEBUG, "Monitor is resetting...\n");
    decoder->mon.wf.num_blocks = 0;
    decoder->mon.max_mag = -120.0f;
    decoder->utcTime = utcTime;
    decoder->num_samples = num_samples;
}

/**
 * Recode 174-bit code to generate 79-bit code
 * @param a174 174 integers (input)
 * @param a79 79 integers (output)
 */
void recode(int a174[], int a79[]) {
    // [FIX] Validate input arrays
    if (a174 == NULL || a79 == NULL) {
        LOG(LOG_ERROR, "recode: NULL pointer passed\n");
        return;
    }

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