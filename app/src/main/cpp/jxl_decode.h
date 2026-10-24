// Decoding of a JPEG XL codestream (a DNG 1.7 tile or strip) to linear 16-bit RGB with libjxl.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

struct JxlPicture {
  uint32_t width = 0;
  uint32_t height = 0;
  // Interleaved R, G, B, linear light, 0..65535; allocated with malloc.
  uint16_t* pixels = nullptr;
  size_t bytes = 0;
};

// Decodes [data] into [out] (pixels owned by the caller, free() them). False with [error] set
// when the codestream cannot be decoded.
bool DecodeJxlLinear(const uint8_t* data, size_t size, JxlPicture* out, std::string* error);
