#include <jni.h>
#include "encode.h"
#include "pack.h"
#include "constants.h"

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_GenerateFT8_generateFt8PayloadDx(
        JNIEnv *env, jobject obj, jstring message, jboolean is_fox_multi) {

    const char *msg_str = env->GetStringUTFChars(message, 0);

    uint8_t packed[FTX_LDPC_K_BYTES];
    int rc = pack77(msg_str, packed);

    env->ReleaseStringUTFChars(message, msg_str);

    if (rc < 0) return NULL;

    // [NEW] Если is_fox_multi = true, переупаковать как мульти-сообщение
    if (is_fox_multi) {
        // Пример: упаковать один позывной как мульти-сообщение (для теста)
        const char *calls_list[1] = { msg_str };
        uint8_t fox_packed[FTX_LDPC_K_BYTES];
        rc = pack_fox_multi(calls_list, 1, fox_packed);
        if (rc == 0) {
            memcpy(packed, fox_packed, FTX_LDPC_K_BYTES);
        }
    }
    // [END NEW]

    jbyteArray result = env->NewByteArray(FTX_LDPC_K_BYTES);
    env->SetByteArrayRegion(result, 0, FTX_LDPC_K_BYTES, (jbyte*)packed);

    return result;
}

} // extern "C"