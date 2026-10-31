#include "example.hpp"

#include <cstdint>

namespace example {

std::uint64_t factorial(std::uint32_t value) noexcept {
    std::uint64_t result = 1;

    for (std::uint32_t current = 2; current <= value; ++current) {
        result *= current;
    }

    return result;
}

bool is_prime(std::uint64_t value) noexcept {
    constexpr std::uint64_t first_prime = 2;

    if (value < first_prime) {
        return false;
    }

    for (std::uint64_t divisor = first_prime; divisor * divisor <= value; ++divisor) {
        if (value % divisor == 0) {
            return false;
        }
    }

    return true;
}

} // namespace example
