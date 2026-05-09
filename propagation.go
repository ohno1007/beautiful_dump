package main

import (
	"strings"
	"unicode"
)

func camel(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	r[0] = unicode.ToLower(r[0])
	return string(r)
}

func lastSegment(qualified string) string {
	if i := strings.LastIndex(qualified, "."); i >= 0 {
		qualified = qualified[i+1:]
	}
	if i := strings.Index(qualified, "<"); i >= 0 {
		qualified = qualified[:i]
	}
	return qualified
}

// nameForTypeField 根据字段类型猜一个 camelCase 字段名。
func nameForTypeField(typeStr string) string {
	if strings.HasSuffix(typeStr, "[]") {
		seg := lastSegment(typeStr[:len(typeStr)-2])
		if seg == "" {
			return ""
		}
		return camel(seg) + "s"
	}
	// 泛型集合 List<X>/Queue<X>/Stack<X>/HashSet<X> → x 复数
	if i := strings.Index(typeStr, "<"); i >= 0 {
		if strings.Contains(typeStr, "List") ||
			strings.Contains(typeStr, "IEnumerable") ||
			strings.Contains(typeStr, "Queue") ||
			strings.Contains(typeStr, "Stack") ||
			strings.Contains(typeStr, "HashSet") {
			rest := typeStr[i+1:]
			j := strings.IndexAny(rest, ",>")
			if j >= 0 {
				inner := rest[:j]
				seg := lastSegment(strings.TrimSpace(inner))
				if seg != "" {
					return camel(seg) + "s"
				}
			}
		}
	}
	seg := lastSegment(typeStr)
	if len(seg) <= 1 {
		return ""
	}
	return camel(seg)
}

// Propagate 在已识别的角色 / 字段类型基础上做名称传播。
// 多轮迭代直到无新增建议或超过 maxPasses。
func Propagate(mod *Module, maxPasses int) int {
	total := 0
	if mod.byOriginal == nil {
		mod.Index()
	}
	byOrig := mod.byOriginal
	for pass := 0; pass < maxPasses; pass++ {
		added := 0
		for _, t := range mod.Types {
			for fi := range t.Fields {
				f := &t.Fields[fi]
				if f.Rename.Locked || f.Rename.Best() != nil {
					continue
				}
				ft := f.Type
				var nm string
				if ref, ok := byOrig[ft]; ok {
					if rb := ref.Rename.Best(); rb != nil {
						nm = camel(lastSegment(rb.Name))
					}
				}
				if nm == "" {
					nm = nameForTypeField(ft)
				}
				if nm != "" && nm != f.Rename.Original {
					f.Rename.Suggest(nm, 0.45, "propagation:field-from-type")
					added++
				}
			}
			for mi := range t.Methods {
				m := &t.Methods[mi]
				if m.Rename.Locked || m.Rename.Best() != nil {
					continue
				}
				if t.Role == "Health" && m.ReturnType == "System.Void" &&
					len(m.Parameters) == 1 && m.Parameters[0].Type == "System.Single" {
					m.Rename.Suggest("TakeDamage", 0.55, "propagation:role-Health")
					added++
				}
				if t.Role == "Weapon" && m.ReturnType == "System.Void" && len(m.Parameters) == 0 {
					already := map[string]struct{}{}
					for _, mm := range t.Methods {
						if &mm != m {
							already[mm.Rename.Display()] = struct{}{}
						}
					}
					if _, has := already["Fire"]; !has {
						m.Rename.Suggest("Fire", 0.5, "propagation:role-Weapon")
						added++
					}
				}
			}
		}
		total += added
		if added == 0 {
			break
		}
	}
	return total
}
