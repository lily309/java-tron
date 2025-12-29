package org.tron.core.vm;

import org.tron.core.capsule.BlockCapsule;

public enum StateType {
  ST_PENDING,
  ST_IN_BLOCK;

  public static StateType from(boolean isConstantCall, BlockCapsule bc) {
    if (!isConstantCall && bc.hasWitnessSignature()) {
      return ST_IN_BLOCK;
    } else {
      return ST_PENDING;
    }
  }

  public boolean shouldCheck() {
    return this != ST_IN_BLOCK;
  }
}
