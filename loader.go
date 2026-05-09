package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// rawJSON 是从输入 JSON 反序列化得到的中间结构。我们用 map[string]any
// 解析以兼容 Il2CppInspector / Il2CppDumper / 自制工具的不同字段大小写。
type rawJSON map[string]any

func pickString(d rawJSON, keys ...string) string {
	for _, k := range keys {
		if v, ok := d[k]; ok {
			switch s := v.(type) {
			case string:
				return s
			case map[string]any:
				return pickString(rawJSON(s), "FullName", "full_name", "Name", "name")
			}
		}
	}
	return ""
}

func pickBool(d rawJSON, keys ...string) bool {
	for _, k := range keys {
		if v, ok := d[k]; ok {
			if b, ok := v.(bool); ok {
				return b
			}
		}
	}
	return false
}

func pickList(d rawJSON, keys ...string) []any {
	for _, k := range keys {
		if v, ok := d[k]; ok {
			if a, ok := v.([]any); ok {
				return a
			}
		}
	}
	return nil
}

func pickInt(d rawJSON, keys ...string) *int64 {
	for _, k := range keys {
		if v, ok := d[k]; ok {
			switch n := v.(type) {
			case float64:
				x := int64(n)
				return &x
			case int64:
				x := n
				return &x
			}
		}
	}
	return nil
}

// splitNamespace 把一个全限定名拆成 (namespace, name)。
// 泛型 < ... > 内的 . 不被视作命名空间分隔符。
func splitNamespace(full string) (string, string) {
	if full == "" {
		return "", ""
	}
	depth := 0
	last := -1
	for i, ch := range full {
		switch ch {
		case '<':
			depth++
		case '>':
			if depth > 0 {
				depth--
			}
		case '.':
			if depth == 0 {
				last = i
			}
		}
	}
	if last < 0 {
		return "", full
	}
	return full[:last], full[last+1:]
}

func attrName(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case map[string]any:
		return pickString(rawJSON(x), "Name", "name", "Type", "type")
	}
	return ""
}

func loadField(raw rawJSON) Field {
	name := pickString(raw, "Name", "name")
	typ := pickString(raw, "Type", "type", "FieldType")
	attrs := []string{}
	for _, a := range pickList(raw, "Attributes", "attributes", "CustomAttributes") {
		if n := attrName(a); n != "" {
			attrs = append(attrs, n)
		}
	}
	serialized := pickBool(raw, "IsSerialized", "is_serialized")
	for _, a := range attrs {
		if strings.Contains(a, "SerializeField") {
			serialized = true
			break
		}
	}
	return Field{
		Rename:       Renameable{Original: name},
		Type:         typ,
		IsStatic:     pickBool(raw, "IsStatic", "is_static"),
		IsPublic:     pickBool(raw, "IsPublic", "is_public"),
		IsSerialized: serialized,
		Attributes:   attrs,
		Offset:       pickInt(raw, "Offset", "offset"),
	}
}

func loadParam(v any) Parameter {
	switch x := v.(type) {
	case string:
		return Parameter{Type: x}
	case map[string]any:
		typ := pickString(rawJSON(x), "Type", "type", "ParameterType")
		return Parameter{
			Name: pickString(rawJSON(x), "Name", "name"),
			Type: typ,
		}
	}
	return Parameter{}
}

func loadMethod(raw rawJSON) Method {
	name := pickString(raw, "Name", "name")
	rt := pickString(raw, "ReturnType", "return_type", "Return")
	if rt == "" {
		rt = "System.Void"
	}
	var params []Parameter
	for _, p := range pickList(raw, "Parameters", "parameters") {
		params = append(params, loadParam(p))
	}
	attrs := []string{}
	for _, a := range pickList(raw, "Attributes", "attributes", "CustomAttributes") {
		if n := attrName(a); n != "" {
			attrs = append(attrs, n)
		}
	}
	isCtor := pickBool(raw, "IsConstructor", "is_constructor") || name == ".ctor" || name == ".cctor"
	return Method{
		Rename:        Renameable{Original: name},
		ReturnType:    rt,
		Parameters:    params,
		IsStatic:      pickBool(raw, "IsStatic", "is_static"),
		IsPublic:      pickBool(raw, "IsPublic", "is_public"),
		IsVirtual:     pickBool(raw, "IsVirtual", "is_virtual"),
		IsOverride:    pickBool(raw, "IsOverride", "is_override"),
		IsAbstract:    pickBool(raw, "IsAbstract", "is_abstract"),
		IsConstructor: isCtor,
		Attributes:    attrs,
		RVA:           pickInt(raw, "RVA", "rva", "Address", "address"),
	}
}

func loadType(raw rawJSON) *Type {
	full := pickString(raw, "FullName", "full_name")
	ns := pickString(raw, "Namespace", "namespace")
	name := pickString(raw, "Name", "name")
	if name == "" && full != "" {
		ns, name = splitNamespace(full)
	}

	t := &Type{
		Rename:        Renameable{Original: name},
		Namespace:     ns,
		BaseType:      pickString(raw, "BaseType", "base_type", "Base"),
		DeclaringType: pickString(raw, "DeclaringType", "declaring_type"),
		IsEnum:        pickBool(raw, "IsEnum", "is_enum"),
		IsInterface:   pickBool(raw, "IsInterface", "is_interface"),
		IsAbstract:    pickBool(raw, "IsAbstract", "is_abstract"),
		IsValueType:   pickBool(raw, "IsValueType", "is_value_type"),
		IsGeneric:     pickBool(raw, "IsGeneric", "is_generic"),
	}

	for _, it := range pickList(raw, "Interfaces", "interfaces") {
		switch x := it.(type) {
		case string:
			t.Interfaces = append(t.Interfaces, x)
		case map[string]any:
			t.Interfaces = append(t.Interfaces, pickString(rawJSON(x), "FullName", "full_name", "Name", "name"))
		}
	}
	for _, a := range pickList(raw, "Attributes", "attributes", "CustomAttributes") {
		if n := attrName(a); n != "" {
			t.Attributes = append(t.Attributes, n)
		}
	}
	for _, f := range pickList(raw, "Fields", "fields") {
		if m, ok := f.(map[string]any); ok {
			t.Fields = append(t.Fields, loadField(rawJSON(m)))
		}
	}
	for _, m := range pickList(raw, "Methods", "methods") {
		if mm, ok := m.(map[string]any); ok {
			t.Methods = append(t.Methods, loadMethod(rawJSON(mm)))
		}
	}
	return t
}

// LoadModule 从一个 JSON 文件加载 Module。支持以下根形态:
//
//	{ "Types": [ ... ] }
//	{ "types": [ ... ] }
//	[ ... ]
func LoadModule(path string) (*Module, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var root any
	if err := json.Unmarshal(data, &root); err != nil {
		return nil, fmt.Errorf("解析 JSON 失败: %w", err)
	}

	var typesRaw []any
	switch r := root.(type) {
	case map[string]any:
		if v, ok := r["Types"].([]any); ok {
			typesRaw = v
		} else if v, ok := r["types"].([]any); ok {
			typesRaw = v
		} else {
			return nil, fmt.Errorf("根对象未找到 Types/types 字段")
		}
	case []any:
		typesRaw = r
	default:
		return nil, fmt.Errorf("不支持的 JSON 根类型 %T", root)
	}

	mod := &Module{}
	for _, t := range typesRaw {
		if m, ok := t.(map[string]any); ok {
			mod.Types = append(mod.Types, loadType(rawJSON(m)))
		}
	}
	mod.Index()
	return mod, nil
}
