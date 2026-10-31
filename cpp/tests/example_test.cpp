#include "example.hpp"

#include <cstdint>

#include <gtest/gtest.h>

namespace {

TEST(FactorialTest, HandlesBaseCases) {
    EXPECT_EQ(example::factorial(0), std::uint64_t{1});
    EXPECT_EQ(example::factorial(1), std::uint64_t{1});
}

TEST(FactorialTest, ComputesSmallValues) {
    constexpr std::uint64_t expected = 120;

    EXPECT_EQ(example::factorial(5), expected);
}

TEST(IsPrimeTest, IdentifiesPrimes) {
    EXPECT_TRUE(example::is_prime(2));
    EXPECT_TRUE(example::is_prime(13));
}

TEST(IsPrimeTest, RejectsNonPrimes) {
    EXPECT_FALSE(example::is_prime(0));
    EXPECT_FALSE(example::is_prime(1));
    EXPECT_FALSE(example::is_prime(9));
}

} // namespace
