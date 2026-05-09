package main

import (
	"sort"
	"strings"
)

// FieldRule 是结构签名的字段规则。
type FieldRule struct {
	Types       []string // 任一匹配即可。
	Weight      float64
	Required    bool
	SuggestName string // 命中时为该字段建议的新名。
}

// MethodRule 是结构签名的方法规则。
type MethodRule struct {
	ReturnType  string   // 空字符串 = 任意。
	ParamTypes  []string // nil = 任意, 否则按位严格相等。
	IsStatic    *bool
	Weight      float64
	Required    bool
	SuggestName string
}

func boolPtr(b bool) *bool { return &b }

// TypeSignature 描述一个完整的类型识别规则。
type TypeSignature struct {
	Name        string
	Role        string
	BaseTypes   []string // 任一匹配即可 (含传递子类)。
	Interfaces  []string // 全部命中。
	Attributes  []string // 全部命中。
	FieldRules  []FieldRule
	MethodRules []MethodRule
	Threshold   float64
	Confidence  float64
	Extra       func(*Type) float64 // 自定义打分增量。
}

type sigMatch struct {
	score   float64
	tField  []*Renameable
	nField  []string
	tMethod []*Renameable
	nMethod []string
}

func anyMatchesBase(t *Type, mod *Module, bases []string) bool {
	if len(bases) == 0 {
		return true
	}
	for _, b := range bases {
		if t.BaseType == b {
			return true
		}
		for _, st := range mod.TransitiveSubclasses(b) {
			if st == t {
				return true
			}
		}
	}
	return false
}

func hasAllInterfaces(t *Type, want []string) bool {
	for _, w := range want {
		found := false
		for _, iv := range t.Interfaces {
			if iv == w || strings.HasSuffix(iv, "."+w) {
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	return true
}

func hasAllAttributes(t *Type, want []string) bool {
	if len(want) == 0 {
		return true
	}
	have := map[string]struct{}{}
	for _, a := range t.Attributes {
		have[a] = struct{}{}
	}
	for _, w := range want {
		if _, ok := have[w]; !ok {
			return false
		}
	}
	return true
}

func fieldTypeMatches(actual string, candidates []string) bool {
	for _, c := range candidates {
		if actual == c || strings.HasSuffix(actual, "."+c) {
			return true
		}
	}
	return false
}

func paramsEqual(actual []Parameter, want []string) bool {
	if want == nil {
		return true
	}
	if len(actual) != len(want) {
		return false
	}
	for i, p := range actual {
		if p.Type != want[i] {
			return false
		}
	}
	return true
}

func scoreType(t *Type, mod *Module, sig *TypeSignature) *sigMatch {
	if !anyMatchesBase(t, mod, sig.BaseTypes) {
		return nil
	}
	if !hasAllInterfaces(t, sig.Interfaces) {
		return nil
	}
	if !hasAllAttributes(t, sig.Attributes) {
		return nil
	}
	m := &sigMatch{}
	for _, rule := range sig.FieldRules {
		var hit *Field
		for fi := range t.Fields {
			if fieldTypeMatches(t.Fields[fi].Type, rule.Types) {
				hit = &t.Fields[fi]
				break
			}
		}
		if hit == nil {
			if rule.Required {
				return nil
			}
			continue
		}
		m.score += rule.Weight
		if rule.SuggestName != "" && !hit.Rename.Locked {
			m.tField = append(m.tField, &hit.Rename)
			m.nField = append(m.nField, rule.SuggestName)
		}
	}
	used := map[int]struct{}{}
	for _, rule := range sig.MethodRules {
		var chosen *Method
		for mi := range t.Methods {
			if _, dup := used[mi]; dup {
				continue
			}
			cur := &t.Methods[mi]
			if rule.ReturnType != "" && cur.ReturnType != rule.ReturnType {
				continue
			}
			if !paramsEqual(cur.Parameters, rule.ParamTypes) {
				continue
			}
			if rule.IsStatic != nil && *rule.IsStatic != cur.IsStatic {
				continue
			}
			chosen = cur
			used[mi] = struct{}{}
			break
		}
		if chosen == nil {
			if rule.Required {
				return nil
			}
			continue
		}
		m.score += rule.Weight
		if rule.SuggestName != "" && !chosen.Rename.Locked {
			m.tMethod = append(m.tMethod, &chosen.Rename)
			m.nMethod = append(m.nMethod, rule.SuggestName)
		}
	}
	if sig.Extra != nil {
		m.score += sig.Extra(t)
	}
	if m.score < sig.Threshold {
		return nil
	}
	return m
}

// ApplySignatures 用一套签名规则在 Module 上提议重命名。
//
// 一个类型可能被多条签名同时打中 (例如某 PlayerController 也含 AudioSource
// 而被 AudioController 命中)。我们让每个类型选出"最强"的那一条签名:
// 类型名只采纳冠军签名提议, 字段/方法重命名也只来自冠军签名 — 避免
// 低分签名把不相干的字段名 (如 muzzlePoint) 泄漏到玩家控制器上。
// 同一签名命中多个类型时, 用 _2, _3 后缀避名字冲突。
func ApplySignatures(mod *Module, sigs []TypeSignature) int {
	type cand struct {
		sig *TypeSignature
		m   *sigMatch
	}

	// 第一遍: 收集每个类型的所有匹配。
	perType := map[*Type][]cand{}
	for si := range sigs {
		sig := &sigs[si]
		for _, t := range mod.Types {
			if t.Rename.Locked {
				continue
			}
			m := scoreType(t, mod, sig)
			if m == nil {
				continue
			}
			perType[t] = append(perType[t], cand{sig, m})
		}
	}

	// 第二遍: 为每个类型挑出冠军签名, 按签名分组以便后缀消歧。
	type hit struct {
		t   *Type
		m   *sigMatch
		sig *TypeSignature
	}
	bySig := map[string][]hit{}
	for t, cs := range perType {
		// 冠军 = 分数 * 置信度乘积最高者。这样高 Threshold 高 Confidence
		// 的强签名 (PlayerController) 能压过弱通用签名 (AudioController)。
		best := cs[0]
		bestScore := cs[0].m.score * cs[0].sig.Confidence
		for i := 1; i < len(cs); i++ {
			s := cs[i].m.score * cs[i].sig.Confidence
			if s > bestScore {
				bestScore = s
				best = cs[i]
			}
		}
		bySig[best.sig.Name] = append(bySig[best.sig.Name], hit{t, best.m, best.sig})
	}

	// 排序保证输出确定: 先按签名名字, 再按命中分数降序。
	names := make([]string, 0, len(bySig))
	for n := range bySig {
		names = append(names, n)
	}
	sort.Strings(names)

	count := 0
	for _, sigName := range names {
		hits := bySig[sigName]
		sort.SliceStable(hits, func(i, j int) bool {
			return hits[i].m.score > hits[j].m.score
		})
		used := map[string]struct{}{}
		for _, h := range hits {
			target := h.sig.Name
			base := target
			k := 2
			for {
				if _, dup := used[target]; !dup {
					break
				}
				target = base + "_" + itoa(k)
				k++
			}
			used[target] = struct{}{}
			h.t.Rename.Suggest(target, h.sig.Confidence, "signature:"+h.sig.Name)
			if h.sig.Role != "" {
				h.t.Role = h.sig.Role
			}
			for i, r := range h.m.tField {
				r.Suggest(h.m.nField[i], h.sig.Confidence-0.05, "signature:"+h.sig.Name)
				count++
			}
			for i, r := range h.m.tMethod {
				r.Suggest(h.m.nMethod[i], h.sig.Confidence-0.05, "signature:"+h.sig.Name)
				count++
			}
			count++
		}
	}
	return count
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := false
	if n < 0 {
		neg = true
		n = -n
	}
	buf := [20]byte{}
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

// AllSignatures 返回内置 Unity + FPS 签名集合。
func AllSignatures() []TypeSignature {
	out := []TypeSignature{}
	out = append(out, UnitySignatures()...)
	out = append(out, FPSSignatures()...)
	return out
}
