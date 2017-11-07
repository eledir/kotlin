// EXPECTED_ERROR(-1;-1) 'WHI-TE' is an invalid Java enum value name
// EXPECTED_ERROR(4;20) an enum annotation value must be an enum constant (Kotlin location: /invalidFieldName.kt: (9, 1))
// EXPECTED_ERROR(4;20) cannot find symbol (Kotlin location: /invalidFieldName.kt: (9, 1))

enum class Color {
    BLACK, `WHI-TE`
}

@Anno(Color.`WHI-TE`)
annotation class Anno(val color: Color)