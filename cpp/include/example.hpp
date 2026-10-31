#pragma once

#include <cstdint>

/// \file example.hpp
/// \brief Minimal example API for the AI-first C++23 project template.
///
/// The functions here exist so the template configures, builds, tests, and
/// documents out of the box. Replace them with your own code.

/// \brief Example library namespace.
namespace example {

/// \brief Computes the factorial of \p value.
///
/// \param value Non-negative input whose factorial is returned.
/// \return \p value! as a 64-bit unsigned integer. Overflows silently for
///         inputs larger than 20, matching unsigned wrap-around semantics.
[[nodiscard]] std::uint64_t factorial(std::uint32_t value) noexcept;

/// \brief Tests whether \p value is a prime number.
///
/// \param value Candidate to test.
/// \return \c true if \p value is prime; \c false otherwise.
[[nodiscard]] bool is_prime(std::uint64_t value) noexcept;

} // namespace example
