#include "jxl_decode.h"

#include <jxl/decode.h>
#include <jxl/thread_parallel_runner.h>

#include <cmath>
#include <cstdlib>
#include <vector>

namespace {

// The decoder and its worker threads, released however decoding ends.
struct Session {
  JxlDecoder* dec = JxlDecoderCreate(nullptr);
  void* runner = JxlThreadParallelRunnerCreate(nullptr, JxlThreadParallelRunnerDefaultNumWorkerThreads());
  ~Session() {
    if (runner) JxlThreadParallelRunnerDestroy(runner);
    if (dec) JxlDecoderDestroy(dec);
  }
};

// Encoded value (0..1) to linear light for the transfer functions cameras use.
double ToLinear(JxlTransferFunction tf, double gamma, double v) {
  switch (tf) {
    case JXL_TRANSFER_FUNCTION_SRGB:
      return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
    case JXL_TRANSFER_FUNCTION_709:
      return v < 0.081 ? v / 4.5 : std::pow((v + 0.099) / 1.099, 1.0 / 0.45);
    case JXL_TRANSFER_FUNCTION_GAMMA:
      // The stored gamma is the encoding exponent (e.g. 1/2.2).
      return gamma > 0 ? std::pow(v, 1.0 / gamma) : v;
    case JXL_TRANSFER_FUNCTION_DCI:
      return std::pow(v, 2.6);
    default:
      return v;
  }
}

}  // namespace

bool DecodeJxlLinear(const uint8_t* data, size_t size, JxlPicture* out, std::string* error) {
  Session s;
  if (!s.dec || !s.runner) {
    *error = "JPEG XL: cannot start the decoder";
    return false;
  }
  if (JxlDecoderSetParallelRunner(s.dec, JxlThreadParallelRunner, s.runner) != JXL_DEC_SUCCESS ||
      JxlDecoderSubscribeEvents(s.dec, JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE) != JXL_DEC_SUCCESS) {
    *error = "JPEG XL: cannot set up the decoder";
    return false;
  }
  // The raw's own layout, as the DNG describes it.
  JxlDecoderSetKeepOrientation(s.dec, JXL_TRUE);
  JxlDecoderSetInput(s.dec, data, size);
  JxlDecoderCloseInput(s.dec);

  JxlBasicInfo info{};
  const JxlPixelFormat format = {3, JXL_TYPE_UINT16, JXL_NATIVE_ENDIAN, 0};
  JxlTransferFunction transfer = JXL_TRANSFER_FUNCTION_LINEAR;
  double gamma = 1.0;
  bool done = false;
  while (!done) {
    switch (JxlDecoderProcessInput(s.dec)) {
      case JXL_DEC_ERROR:
        *error = "JPEG XL: the data cannot be decoded";
        free(out->pixels);
        out->pixels = nullptr;
        return false;
      case JXL_DEC_NEED_MORE_INPUT:
        *error = "JPEG XL: the data ends early";
        free(out->pixels);
        out->pixels = nullptr;
        return false;
      case JXL_DEC_BASIC_INFO:
        JxlDecoderGetBasicInfo(s.dec, &info);
        out->width = info.xsize;
        out->height = info.ysize;
        break;
      case JXL_DEC_COLOR_ENCODING: {
        // Lossy (XYB) data can come out in any encoding: the original one, with linear light.
        JxlColorEncoding original{};
        if (!info.uses_original_profile &&
            JxlDecoderGetColorAsEncodedProfile(s.dec, JXL_COLOR_PROFILE_TARGET_ORIGINAL, &original) == JXL_DEC_SUCCESS) {
          original.transfer_function = JXL_TRANSFER_FUNCTION_LINEAR;
          JxlDecoderSetPreferredColorProfile(s.dec, &original);
        }
        JxlColorEncoding target{};
        if (JxlDecoderGetColorAsEncodedProfile(s.dec, JXL_COLOR_PROFILE_TARGET_DATA, &target) == JXL_DEC_SUCCESS) {
          transfer = target.transfer_function;
          gamma = target.gamma;
        }
        break;
      }
      case JXL_DEC_NEED_IMAGE_OUT_BUFFER: {
        size_t bytes = 0;
        if (JxlDecoderImageOutBufferSize(s.dec, &format, &bytes) != JXL_DEC_SUCCESS) {
          *error = "JPEG XL: unknown picture size";
          return false;
        }
        free(out->pixels);
        out->pixels = static_cast<uint16_t*>(malloc(bytes));
        out->bytes = bytes;
        if (!out->pixels) {
          *error = "Not enough memory to develop this file";
          return false;
        }
        if (JxlDecoderSetImageOutBuffer(s.dec, &format, out->pixels, bytes) != JXL_DEC_SUCCESS) {
          *error = "JPEG XL: cannot take the picture";
          free(out->pixels);
          out->pixels = nullptr;
          return false;
        }
        break;
      }
      case JXL_DEC_FULL_IMAGE:
        // One picture is all a DNG tile holds.
        done = true;
        break;
      case JXL_DEC_SUCCESS:
        done = true;
        break;
      default:
        break;
    }
  }
  if (!out->pixels) {
    *error = "JPEG XL: no picture in the data";
    return false;
  }
  if (transfer != JXL_TRANSFER_FUNCTION_LINEAR) {
    std::vector<uint16_t> table(65536);
    for (size_t i = 0; i < table.size(); i++) {
      const double v = ToLinear(transfer, gamma, i / 65535.0);
      table[i] = static_cast<uint16_t>(std::lround(std::fmin(1.0, std::fmax(0.0, v)) * 65535.0));
    }
    const size_t count = out->bytes / 2;
    for (size_t i = 0; i < count; i++) out->pixels[i] = table[out->pixels[i]];
  }
  return true;
}
