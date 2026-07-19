package com.ahmedali.fulfillops.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockLevelTest {

  @Test
  void reservingMoreThanAvailableThrowsAndLeavesQuantitiesUnchanged() {
    StockLevel stock = new StockLevel(UUID.randomUUID(), "SKU-1");
    stock.adjust(5);

    assertThatThrownBy(() -> stock.reserve(6)).isInstanceOf(IllegalStateException.class);
    assertThat(stock.getAvailableQuantity()).isEqualTo(5);
    assertThat(stock.getReservedQuantity()).isZero();
  }

  @Test
  void reservingWithinAvailableMovesQuantityFromAvailableToReserved() {
    StockLevel stock = new StockLevel(UUID.randomUUID(), "SKU-1");
    stock.adjust(5);

    stock.reserve(3);

    assertThat(stock.getAvailableQuantity()).isEqualTo(2);
    assertThat(stock.getReservedQuantity()).isEqualTo(3);
  }

  @Test
  void releaseMovesQuantityBackFromReservedToAvailable() {
    StockLevel stock = new StockLevel(UUID.randomUUID(), "SKU-1");
    stock.adjust(5);
    stock.reserve(3);

    stock.release(3);

    assertThat(stock.getAvailableQuantity()).isEqualTo(5);
    assertThat(stock.getReservedQuantity()).isZero();
  }

  @Test
  void adjustingBelowZeroThrowsAndLeavesQuantityUnchanged() {
    StockLevel stock = new StockLevel(UUID.randomUUID(), "SKU-1");
    stock.adjust(5);

    assertThatThrownBy(() -> stock.adjust(-10)).isInstanceOf(IllegalStateException.class);
    assertThat(stock.getAvailableQuantity()).isEqualTo(5);
  }

  @Test
  void hasAvailableReflectsCurrentAvailableQuantity() {
    StockLevel stock = new StockLevel(UUID.randomUUID(), "SKU-1");
    stock.adjust(5);

    assertThat(stock.hasAvailable(5)).isTrue();
    assertThat(stock.hasAvailable(6)).isFalse();
  }
}
