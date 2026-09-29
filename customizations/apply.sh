#!/bin/bash
set -e

echo "=== 应用自定义改动 ==="

# 改动1: 可执行槽位上限改为10
SLOT_FILE="app/src/main/java/io/github/aoguai/sesameag/hook/AccountSlotRegistry.kt"
if [ -f "$SLOT_FILE" ]; then
    sed -i 's/const val MAX_EXECUTABLE_ACCOUNT_SLOTS = [0-9]*/const val MAX_EXECUTABLE_ACCOUNT_SLOTS = 10/' "$SLOT_FILE"
    echo "✅ 槽位上限=10 ($SLOT_FILE)"
else
    echo "⚠️ 未找到 $SLOT_FILE，跳过"
fi

echo "=== 自定义改动应用完成 ==="
