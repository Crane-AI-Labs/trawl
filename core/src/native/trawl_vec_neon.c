/*
 * Zero-dependency ARM64 NEON SDOT kernel for Trawl SQ8 (int8) vectors.bin v3.
 *
 * Uses 2-term residual query quantization (q ≈ q_hi * q_hi_scale + q_lo * q_lo_scale)
 * so every vector byte is loaded from DRAM only once while matching float32-query SQ8
 * dot products to < 1e-5 (15-bit query precision).
 *
 * Compiled on macOS ARM64 (and optionally Linux ARM64) into libtrawl_vec_neon.{dylib,so}.
 * Pure-JVM fallback in Sq8.kt handles x86_64, Windows, and any host without this library.
 */

#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#define TRAWL_HAVE_NEON 1
#else
#define TRAWL_HAVE_NEON 0
#endif

#define STACK_DIM_MAX 2048
#define BLOCK_ROWS 1024
#define HEAP_BLOCK_BYTES (64 * 1024)

static inline float read_f32(const uint8_t *ptr) {
  float v;
  memcpy(&v, ptr, sizeof(float));
  return v;
}

static inline float dot_sq8_2term(
    const int8_t *v,
    const int8_t *q_hi,
    const int8_t *q_lo,
    int dim,
    float v_scale,
    float q_hi_scale,
    float q_lo_scale) {
  if (!isfinite(v_scale) || v_scale == 0.0f || !isfinite(q_hi_scale) || !isfinite(q_lo_scale)) {
    return 0.0f;
  }
  int32_t sum_hi = 0;
  int32_t sum_lo = 0;
  int i = 0;

#if TRAWL_HAVE_NEON && defined(__ARM_FEATURE_DOTPROD)
  int32x4_t h0 = vdupq_n_s32(0), h1 = vdupq_n_s32(0), h2 = vdupq_n_s32(0), h3 = vdupq_n_s32(0);
  int32x4_t l0 = vdupq_n_s32(0), l1 = vdupq_n_s32(0), l2 = vdupq_n_s32(0), l3 = vdupq_n_s32(0);
  for (; i + 64 <= dim; i += 64) {
    int8x16_t v0 = vld1q_s8(v + i);
    int8x16_t v1 = vld1q_s8(v + i + 16);
    int8x16_t v2 = vld1q_s8(v + i + 32);
    int8x16_t v3 = vld1q_s8(v + i + 48);

    h0 = vdotq_s32(h0, v0, vld1q_s8(q_hi + i));
    l0 = vdotq_s32(l0, v0, vld1q_s8(q_lo + i));
    h1 = vdotq_s32(h1, v1, vld1q_s8(q_hi + i + 16));
    l1 = vdotq_s32(l1, v1, vld1q_s8(q_lo + i + 16));
    h2 = vdotq_s32(h2, v2, vld1q_s8(q_hi + i + 32));
    l2 = vdotq_s32(l2, v2, vld1q_s8(q_lo + i + 32));
    h3 = vdotq_s32(h3, v3, vld1q_s8(q_hi + i + 48));
    l3 = vdotq_s32(l3, v3, vld1q_s8(q_lo + i + 48));
  }
  for (; i + 16 <= dim; i += 16) {
    int8x16_t v0 = vld1q_s8(v + i);
    h0 = vdotq_s32(h0, v0, vld1q_s8(q_hi + i));
    l0 = vdotq_s32(l0, v0, vld1q_s8(q_lo + i));
  }
  sum_hi = vaddvq_s32(vaddq_s32(vaddq_s32(h0, h1), vaddq_s32(h2, h3)));
  sum_lo = vaddvq_s32(vaddq_s32(vaddq_s32(l0, l1), vaddq_s32(l2, l3)));
#endif

  for (; i < dim; i++) {
    int32_t vi = (int32_t)v[i];
    sum_hi += vi * (int32_t)q_hi[i];
    sum_lo += vi * (int32_t)q_lo[i];
  }
  double prod = ((double)sum_hi * (double)q_hi_scale + (double)sum_lo * (double)q_lo_scale) * (double)v_scale;
  return isfinite(prod) ? (float)prod : 0.0f;
}

static int load_query(
    JNIEnv *env,
    jbyteArray j_qhi,
    jbyteArray j_qlo,
    int dim,
    int8_t *stack_hi,
    int8_t *stack_lo,
    int8_t **out_hi,
    int8_t **out_lo,
    int *heap_alloc) {
  if (dim <= 0 || !j_qhi || !j_qlo) return 0;
  jsize hi_len = (*env)->GetArrayLength(env, j_qhi);
  jsize lo_len = (*env)->GetArrayLength(env, j_qlo);
  if (hi_len < dim || lo_len < dim) return 0;
  if (dim <= STACK_DIM_MAX) {
    *out_hi = stack_hi;
    *out_lo = stack_lo;
    *heap_alloc = 0;
  } else {
    *out_hi = (int8_t *)malloc((size_t)dim);
    *out_lo = (int8_t *)malloc((size_t)dim);
    if (!*out_hi || !*out_lo) {
      free(*out_hi);
      free(*out_lo);
      *out_hi = NULL;
      *out_lo = NULL;
      return 0;
    }
    *heap_alloc = 1;
  }
  (*env)->GetByteArrayRegion(env, j_qhi, 0, (jsize)dim, (jbyte *)*out_hi);
  if ((*env)->ExceptionCheck(env)) {
    if (*heap_alloc) { free(*out_hi); free(*out_lo); *heap_alloc = 0; }
    return 0;
  }
  (*env)->GetByteArrayRegion(env, j_qlo, 0, (jsize)dim, (jbyte *)*out_lo);
  if ((*env)->ExceptionCheck(env)) {
    if (*heap_alloc) { free(*out_hi); free(*out_lo); *heap_alloc = 0; }
    return 0;
  }
  return 1;
}

JNIEXPORT jint JNICALL Java_com_craneai_findr_search_Sq8Native_probe(JNIEnv *env, jclass cls) {
  (void)env;
  (void)cls;
#if TRAWL_HAVE_NEON && defined(__ARM_FEATURE_DOTPROD)
  return 2; /* ARM64 NEON SDOT active */
#else
  return 1; /* Scalar C fallback */
#endif
}

JNIEXPORT void JNICALL Java_com_craneai_findr_search_Sq8Native_scoreChunkDirect(
    JNIEnv *env,
    jclass cls,
    jobject chunk,
    jint vecsOffset,
    jint scalesOffset,
    jint rowFrom,
    jint rowTo,
    jint dim,
    jbyteArray j_qhi,
    jfloat qHiScale,
    jbyteArray j_qlo,
    jfloat qLoScale,
    jfloatArray out,
    jint outOffset) {
  (void)cls;
  if (!chunk || !out || rowTo <= rowFrom || dim <= 0 || rowFrom < 0 || outOffset < 0) return;
  jlong out_len = (jlong)(*env)->GetArrayLength(env, out);
  if ((jlong)outOffset + (jlong)(rowTo - rowFrom) > out_len) return;

  const uint8_t *base = (const uint8_t *)(*env)->GetDirectBufferAddress(env, chunk);
  jlong cap = (*env)->GetDirectBufferCapacity(env, chunk);
  if (!base || cap <= 0) return;

  jlong vec_end = (jlong)vecsOffset + (jlong)rowTo * (jlong)dim;
  jlong scale_end = (jlong)scalesOffset + (jlong)rowTo * 4LL;
  if (vecsOffset < 0 || scalesOffset < 0 || vec_end > cap || scale_end > cap) return;

  int8_t stack_hi[STACK_DIM_MAX];
  int8_t stack_lo[STACK_DIM_MAX];
  int8_t *q_hi = NULL;
  int8_t *q_lo = NULL;
  int heap_alloc = 0;
  if (!load_query(env, j_qhi, j_qlo, (int)dim, stack_hi, stack_lo, &q_hi, &q_lo, &heap_alloc)) return;

  const int8_t *vecs_base = (const int8_t *)(base + vecsOffset);
  const uint8_t *scales_base = base + scalesOffset;
  float scores_buf[BLOCK_ROWS];

  int row = (int)rowFrom;
  int end = (int)rowTo;
  int dst = (int)outOffset;
  while (row < end) {
    int n = end - row;
    if (n > BLOCK_ROWS) n = BLOCK_ROWS;
    for (int k = 0; k < n; k++) {
      int r = row + k;
      float sc = read_f32(scales_base + (size_t)r * 4U);
      scores_buf[k] = dot_sq8_2term(vecs_base + (size_t)r * (size_t)dim, q_hi, q_lo, (int)dim, sc, qHiScale, qLoScale);
    }
    (*env)->SetFloatArrayRegion(env, out, (jsize)dst, (jsize)n, scores_buf);
    if ((*env)->ExceptionCheck(env)) break;
    row += n;
    dst += n;
  }

  if (heap_alloc) {
    free(q_hi);
    free(q_lo);
  }
}

JNIEXPORT void JNICALL Java_com_craneai_findr_search_Sq8Native_scoreChunkDirectFiltered(
    JNIEnv *env,
    jclass cls,
    jobject chunk,
    jint vecsOffset,
    jint scalesOffset,
    jint rowFrom,
    jint rowTo,
    jint dim,
    jbyteArray j_qhi,
    jfloat qHiScale,
    jbyteArray j_qlo,
    jfloat qLoScale,
    jbyteArray j_kinds,
    jint kindsOffset,
    jbooleanArray j_allowed,
    jfloatArray out,
    jint outOffset) {
  (void)cls;
  if (!chunk || !j_kinds || !j_allowed || !out || rowTo <= rowFrom || dim <= 0 || rowFrom < 0 || kindsOffset < 0 || outOffset < 0) return;
  jlong count = (jlong)rowTo - (jlong)rowFrom;
  if ((jlong)kindsOffset + count > (jlong)(*env)->GetArrayLength(env, j_kinds)) return;
  if ((jlong)outOffset + count > (jlong)(*env)->GetArrayLength(env, out)) return;

  const uint8_t *base = (const uint8_t *)(*env)->GetDirectBufferAddress(env, chunk);
  jlong cap = (*env)->GetDirectBufferCapacity(env, chunk);
  if (!base || cap <= 0) return;

  jlong vec_end = (jlong)vecsOffset + (jlong)rowTo * (jlong)dim;
  jlong scale_end = (jlong)scalesOffset + (jlong)rowTo * 4LL;
  if (vecsOffset < 0 || scalesOffset < 0 || vec_end > cap || scale_end > cap) return;

  jsize allowed_len = (*env)->GetArrayLength(env, j_allowed);
  if (allowed_len <= 0 || allowed_len > 64) return;
  jboolean allowed[64];
  (*env)->GetBooleanArrayRegion(env, j_allowed, 0, allowed_len, allowed);
  if ((*env)->ExceptionCheck(env)) return;

  int8_t stack_hi[STACK_DIM_MAX];
  int8_t stack_lo[STACK_DIM_MAX];
  int8_t *q_hi = NULL;
  int8_t *q_lo = NULL;
  int heap_alloc = 0;
  if (!load_query(env, j_qhi, j_qlo, (int)dim, stack_hi, stack_lo, &q_hi, &q_lo, &heap_alloc)) return;

  const int8_t *vecs_base = (const int8_t *)(base + vecsOffset);
  const uint8_t *scales_base = base + scalesOffset;
  float scores_buf[BLOCK_ROWS];
  jbyte kinds_buf[BLOCK_ROWS];

  int row = (int)rowFrom;
  int end = (int)rowTo;
  int k_off = (int)kindsOffset;
  int dst = (int)outOffset;
  while (row < end) {
    int n = end - row;
    if (n > BLOCK_ROWS) n = BLOCK_ROWS;
    (*env)->GetByteArrayRegion(env, j_kinds, (jsize)k_off, (jsize)n, kinds_buf);
    if ((*env)->ExceptionCheck(env)) break;
    for (int k = 0; k < n; k++) {
      int kind = (int)(uint8_t)kinds_buf[k];
      if (kind >= (int)allowed_len || !allowed[kind]) {
        scores_buf[k] = -INFINITY;
        continue;
      }
      int r = row + k;
      float sc = read_f32(scales_base + (size_t)r * 4U);
      scores_buf[k] = dot_sq8_2term(vecs_base + (size_t)r * (size_t)dim, q_hi, q_lo, (int)dim, sc, qHiScale, qLoScale);
    }
    (*env)->SetFloatArrayRegion(env, out, (jsize)dst, (jsize)n, scores_buf);
    if ((*env)->ExceptionCheck(env)) break;
    row += n;
    k_off += n;
    dst += n;
  }

  if (heap_alloc) {
    free(q_hi);
    free(q_lo);
  }
}

JNIEXPORT void JNICALL Java_com_craneai_findr_search_Sq8Native_scoreRowsDirect(
    JNIEnv *env,
    jclass cls,
    jobject chunk,
    jint vecsOffset,
    jint scalesOffset,
    jintArray j_rows,
    jint rowsFrom,
    jint rowsTo,
    jint dim,
    jbyteArray j_qhi,
    jfloat qHiScale,
    jbyteArray j_qlo,
    jfloat qLoScale,
    jfloatArray out,
    jint outOffset) {
  (void)cls;
  if (!chunk || !j_rows || !out || rowsTo <= rowsFrom || dim <= 0 || rowsFrom < 0 || outOffset < 0) return;
  if ((jlong)rowsTo > (jlong)(*env)->GetArrayLength(env, j_rows)) return;
  if ((jlong)outOffset + ((jlong)rowsTo - (jlong)rowsFrom) > (jlong)(*env)->GetArrayLength(env, out)) return;

  const uint8_t *base = (const uint8_t *)(*env)->GetDirectBufferAddress(env, chunk);
  jlong cap = (*env)->GetDirectBufferCapacity(env, chunk);
  if (!base || cap <= 0 || vecsOffset < 0 || scalesOffset < 0) return;

  int8_t stack_hi[STACK_DIM_MAX];
  int8_t stack_lo[STACK_DIM_MAX];
  int8_t *q_hi = NULL;
  int8_t *q_lo = NULL;
  int heap_alloc = 0;
  if (!load_query(env, j_qhi, j_qlo, (int)dim, stack_hi, stack_lo, &q_hi, &q_lo, &heap_alloc)) return;

  const int8_t *vecs_base = (const int8_t *)(base + vecsOffset);
  const uint8_t *scales_base = base + scalesOffset;
  jint rows_buf[BLOCK_ROWS];
  float scores_buf[BLOCK_ROWS];

  int pos = (int)rowsFrom;
  int end = (int)rowsTo;
  int dst = (int)outOffset;
  while (pos < end) {
    int n = end - pos;
    if (n > BLOCK_ROWS) n = BLOCK_ROWS;
    (*env)->GetIntArrayRegion(env, j_rows, (jsize)pos, (jsize)n, rows_buf);
    if ((*env)->ExceptionCheck(env)) break;
    for (int k = 0; k < n; k++) {
      int r = (int)rows_buf[k];
      if (r < 0) {
        scores_buf[k] = 0.0f;
        continue;
      }
      jlong next_row = (jlong)r + 1LL;
      jlong vec_end = (jlong)vecsOffset + next_row * (jlong)dim;
      jlong scale_end = (jlong)scalesOffset + next_row * 4LL;
      if (vec_end > cap || scale_end > cap) {
        scores_buf[k] = 0.0f;
        continue;
      }
      float sc = read_f32(scales_base + (size_t)r * 4U);
      scores_buf[k] = dot_sq8_2term(vecs_base + (size_t)r * (size_t)dim, q_hi, q_lo, (int)dim, sc, qHiScale, qLoScale);
    }
    (*env)->SetFloatArrayRegion(env, out, (jsize)dst, (jsize)n, scores_buf);
    if ((*env)->ExceptionCheck(env)) break;
    pos += n;
    dst += n;
  }

  if (heap_alloc) {
    free(q_hi);
    free(q_lo);
  }
}

JNIEXPORT void JNICALL Java_com_craneai_findr_search_Sq8Native_scoreHeap(
    JNIEnv *env,
    jclass cls,
    jbyteArray j_vecs,
    jfloatArray j_scales,
    jint rowFrom,
    jint rowTo,
    jint dim,
    jbyteArray j_qhi,
    jfloat qHiScale,
    jbyteArray j_qlo,
    jfloat qLoScale,
    jfloatArray out,
    jint outOffset) {
  (void)cls;
  if (!j_vecs || !j_scales || !out || rowTo <= rowFrom || dim <= 0 || rowFrom < 0 || outOffset < 0) return;
  jlong vecs_len = (jlong)(*env)->GetArrayLength(env, j_vecs);
  jlong scales_len = (jlong)(*env)->GetArrayLength(env, j_scales);
  jlong out_len = (jlong)(*env)->GetArrayLength(env, out);
  if ((jlong)rowTo * (jlong)dim > vecs_len) return;
  if ((jlong)rowTo > scales_len) return;
  if ((jlong)outOffset + ((jlong)rowTo - (jlong)rowFrom) > out_len) return;

  int8_t stack_hi[STACK_DIM_MAX];
  int8_t stack_lo[STACK_DIM_MAX];
  int8_t *q_hi = NULL;
  int8_t *q_lo = NULL;
  int heap_alloc = 0;
  if (!load_query(env, j_qhi, j_qlo, (int)dim, stack_hi, stack_lo, &q_hi, &q_lo, &heap_alloc)) return;

  int total_rows = (int)(rowTo - rowFrom);
  int block_rows = HEAP_BLOCK_BYTES / (int)dim;
  if (block_rows > 64) block_rows = 64;
  if (block_rows < 1) block_rows = 1;
  int8_t *vec_buf = (int8_t *)malloc((size_t)block_rows * (size_t)dim);
  float scale_buf[64];
  float score_buf[64];
  if (!vec_buf) {
    if (heap_alloc) { free(q_hi); free(q_lo); }
    return;
  }

  int row = (int)rowFrom;
  int dst = (int)outOffset;
  int left = total_rows;
  while (left > 0) {
    int n = left < block_rows ? left : block_rows;
    (*env)->GetByteArrayRegion(env, j_vecs, (jsize)((jlong)row * (jlong)dim), (jsize)((jlong)n * (jlong)dim), (jbyte *)vec_buf);
    if ((*env)->ExceptionCheck(env)) break;
    (*env)->GetFloatArrayRegion(env, j_scales, (jsize)row, (jsize)n, scale_buf);
    if ((*env)->ExceptionCheck(env)) break;
    for (int k = 0; k < n; k++) {
      score_buf[k] = dot_sq8_2term(vec_buf + (size_t)k * (size_t)dim, q_hi, q_lo, (int)dim, scale_buf[k], qHiScale, qLoScale);
    }
    (*env)->SetFloatArrayRegion(env, out, (jsize)dst, (jsize)n, score_buf);
    if ((*env)->ExceptionCheck(env)) break;
    row += n;
    dst += n;
    left -= n;
  }

  free(vec_buf);
  if (heap_alloc) {
    free(q_hi);
    free(q_lo);
  }
}
