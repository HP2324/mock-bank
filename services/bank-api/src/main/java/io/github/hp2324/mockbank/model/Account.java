package io.github.hp2324.mockbank.model;

import java.math.BigDecimal;

public record Account(String number, String type, BigDecimal balance) {
}
