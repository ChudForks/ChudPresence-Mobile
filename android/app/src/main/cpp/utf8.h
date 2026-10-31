#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace chudpresence {
// JNI's GetStringUTFChars uses modified UTF-8, which encodes emoji as invalid UTF-8
// surrogate sequences. Discord expects ordinary UTF-8 strings.
inline std::string Utf16ToUtf8(const std::uint16_t* text, std::size_t length) {
    std::string result;
    result.reserve(length);
    for (std::size_t index = 0; index < length; ++index) {
        std::uint32_t code_point = text[index];
        if (code_point >= 0xd800 && code_point <= 0xdbff) {
            if (index + 1 < length && text[index + 1] >= 0xdc00 && text[index + 1] <= 0xdfff) {
                code_point = 0x10000 + ((code_point - 0xd800) << 10) + (text[++index] - 0xdc00);
            } else {
                code_point = 0xfffd;
            }
        } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
            code_point = 0xfffd;
        }

        if (code_point < 0x80) {
            result.push_back(static_cast<char>(code_point));
        } else if (code_point < 0x800) {
            result.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
        } else if (code_point < 0x10000) {
            result.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
        } else {
            result.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
        }
    }
    return result;
}
}
