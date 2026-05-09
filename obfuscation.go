package main

import (
	"regexp"
	"strings"
	"unicode"
)

// 已知 SDK 命名空间前缀 — 处于这些命名空间内的类型默认锁定。
var knownNamespacePrefixes = []string{
	"System",
	"UnityEngine",
	"Unity.",
	"UnityEditor",
	"Mono",
	"Microsoft",
	"JetBrains",
	"TMPro",
	"Cinemachine",
	"TextMeshPro",
	"Photon",
	"ExitGames",
	"Mirror",
	"Telepathy",
	"kcp2k",
	"FishNet",
	"Bolt",
	"Steamworks",
	"Facepunch",
	"Newtonsoft",
	"Google",
	"Firebase",
	"DG.Tweening",
	"Sirenix",
	"MoreMountains",
	"Rewired",
	"BehaviorDesigner",
	"PlayMaker",
	"Spine",
	"FMODUnity",
	"FMOD",
	"Wwise",
}

// 看起来"短"但实际上很常见的合法标识符 — 不要把它们当成混淆名。
var keepNames = map[string]struct{}{
	"T": {}, "U": {}, "V": {}, "K": {},
	"ID": {}, "Id": {},
	"X": {}, "Y": {}, "Z": {}, "W": {},
	"UI": {}, "IO": {}, "IL": {},
	"OK": {}, "On": {}, "Up": {}, "Go": {}, "No": {},
}

var lookalikeLetters = map[rune]struct{}{
	'I': {}, 'l': {}, '1': {}, 'O': {}, '0': {},
}

// 零宽字符: ZWSP (U+200B), ZWNJ (U+200C), ZWJ (U+200D), BOM/ZWNBSP (U+FEFF), WJ (U+2060)。
var zeroWidth = map[rune]struct{}{
	'\u200B': {}, '\u200C': {}, '\u200D': {}, '\uFEFF': {}, '\u2060': {},
}

var (
	reHexBlob = regexp.MustCompile(`^_?\$?[0-9a-fA-F]{4,}$`)
	reSynth   = regexp.MustCompile(`^<.*>[a-z]__\d+$`)
)

// IsInKnownNamespace 判断命名空间是否属于已知 SDK。
func IsInKnownNamespace(ns string) bool {
	if ns == "" {
		return false
	}
	for _, p := range knownNamespacePrefixes {
		trim := strings.TrimSuffix(p, ".")
		if ns == trim || strings.HasPrefix(ns, p) {
			return true
		}
	}
	return false
}

// IsCompilerGenerated 判断是否为编译器生成的合成名 (如 <Foo>d__1)。
func IsCompilerGenerated(name string) bool {
	if reSynth.MatchString(name) {
		return true
	}
	return strings.Contains(name, "<") && strings.Contains(name, ">")
}

// LooksObfuscated 判断一个标识符看起来是否被混淆器重命名过。
// 误判方向: 倾向于"宁可漏掉(false negative)也不要错杀(false positive)"。
func LooksObfuscated(name string) bool {
	if name == "" {
		return false
	}
	if _, ok := keepNames[name]; ok {
		return false
	}
	if IsCompilerGenerated(name) {
		return false
	}
	if strings.HasPrefix(name, ".") {
		return false
	}

	// 零宽字符 / 控制字符。
	for _, r := range name {
		if _, ok := zeroWidth[r]; ok {
			return true
		}
		if r < 0x20 {
			return true
		}
	}

	stripped := strings.TrimLeft(name, "_")

	// 全部由 lookalike 字母构成 (Il1, IlIIIl, ...)。
	if stripped != "" {
		all := true
		for _, r := range stripped {
			if _, ok := lookalikeLetters[r]; !ok {
				all = false
				break
			}
		}
		if all {
			return true
		}
	}

	// 1-2 字母短名且不在保留表里 — 典型 BeeByte 输出。
	if l := len([]rune(stripped)); l > 0 && l <= 2 {
		allAlpha := true
		for _, r := range stripped {
			if !unicode.IsLetter(r) {
				allAlpha = false
				break
			}
		}
		if allAlpha {
			return true
		}
	}

	// 16 进制风格的随机串。
	if reHexBlob.MatchString(name) {
		return true
	}

	// 大量非 ASCII 字符 (CJK 字形混淆)。
	nonAscii := 0
	for _, r := range name {
		if r > 0x7F {
			nonAscii++
		}
	}
	if nonAscii > 0 && float64(nonAscii)/float64(len([]rune(name))) > 0.5 {
		return true
	}

	// 主要是标点 / 下划线。
	alnum := 0
	for _, r := range name {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			alnum++
		}
	}
	if alnum < max(1, len(name)/3) {
		return true
	}

	// 4+ 字母, 全大写或全小写, 没有元音 — 看起来像随机噪声。
	if l := len([]rune(stripped)); l >= 4 {
		allAlpha := true
		for _, r := range stripped {
			if !unicode.IsLetter(r) {
				allAlpha = false
				break
			}
		}
		if allAlpha {
			lower := strings.ToLower(stripped)
			vowels := strings.ContainsAny(lower, "aeiou")
			isUp := stripped == strings.ToUpper(stripped)
			isLow := stripped == strings.ToLower(stripped)
			if !vowels && (isUp || isLow) {
				return true
			}
		}
	}

	return false
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// LockAnchors 锁定所有可被认定为非混淆的标识符。
// 返回此次新锁定的标识符数量 (用于报告)。
func LockAnchors(mod *Module) int {
	count := 0
	lock := func(r *Renameable) {
		if !r.Locked {
			r.Locked = true
			count++
		}
	}

	for _, t := range mod.Types {
		nsLocked := IsInKnownNamespace(t.Namespace)
		synth := IsCompilerGenerated(t.Rename.Original)
		clean := !LooksObfuscated(t.Rename.Original)

		if nsLocked || synth || clean {
			lock(&t.Rename)
		}

		for fi := range t.Fields {
			f := &t.Fields[fi]
			if t.Rename.Locked || !LooksObfuscated(f.Rename.Original) {
				lock(&f.Rename)
			}
		}
		for mi := range t.Methods {
			m := &t.Methods[mi]
			if m.IsConstructor {
				lock(&m.Rename)
				continue
			}
			if t.Rename.Locked || !LooksObfuscated(m.Rename.Original) {
				lock(&m.Rename)
			}
		}
	}
	return count
}
