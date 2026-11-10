#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    printf '用法: %s <adb devices 中的电视序列号或 IP:端口>\n' "$0" >&2
    exit 2
fi

device=(adb -s "$1")
"${device[@]}" get-state >/dev/null

printf '\n== 设备与固件 ==\n'
"${device[@]}" shell 'for p in ro.product.model ro.build.version.release ro.build.version.sdk ro.build.fingerprint ro.product.cpu.abilist; do printf "%s=" "$p"; getprop "$p"; done'

printf '\n== 当前默认桌面 ==\n'
"${device[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME

printf '\n== 设备功能 ==\n'
"${device[@]}" shell pm list features | awk '/leanback|television|device_admin/'

printf '\n== 已安装的第三方应用 ==\n'
"${device[@]}" shell pm list packages -3

printf '\n== 电源状态 ==\n'
"${device[@]}" shell dumpsys power | awk '/mWakefulness=|mInteractive=|Display Power: state=/'

printf '\n== 无障碍服务 ==\n'
"${device[@]}" shell settings get secure enabled_accessibility_services
