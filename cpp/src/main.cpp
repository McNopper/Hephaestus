#include "example.hpp"

#include <cstdint>
#include <iostream>

int main() {
    constexpr std::uint32_t sample = 6;

    std::cout << "factorial(" << sample << ") = " << example::factorial(sample) << '\n';
    std::cout << sample << " is " << (example::is_prime(sample) ? "prime" : "not prime") << '\n';

    return 0;
}
