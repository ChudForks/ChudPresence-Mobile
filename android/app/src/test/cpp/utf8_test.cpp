#include "../../main/cpp/utf8.h"
#include <cassert>

int main() {
    using chudpresence::Utf16ToUtf8;
    const std::uint16_t ordinary[] = {'A', 0x00e9, 0x4e2d};
    assert(Utf16ToUtf8(ordinary, 3) == "A\xc3\xa9\xe4\xb8\xad");

    // U+1F9E0 must be four UTF-8 bytes, not JNI's six-byte modified UTF-8 encoding.
    const std::uint16_t emoji[] = {0xd83e, 0xdde0};
    const auto actual = Utf16ToUtf8(emoji, 2);
    assert(actual == "\xf0\x9f\xa7\xa0");
    assert(actual.size() == 4);
    assert(actual != "\xed\xa0\xbe\xed\xb7\xa0");

    const std::uint16_t broken[] = {0xd83e, 'A', 0xdde0};
    assert(Utf16ToUtf8(broken, 3) == "\xef\xbf\xbd" "A" "\xef\xbf\xbd");
    const std::uint16_t zero[] = {0};
    assert(Utf16ToUtf8(zero, 1) == std::string(1, '\0'));
    assert(Utf16ToUtf8(nullptr, 0).empty());
}
