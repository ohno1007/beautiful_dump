package main

// Suggestion 表示一个候选名称及其置信度。
type Suggestion struct {
	Name       string
	Confidence float64
	Reason     string
}

// Renameable 包装一个可被重命名的标识符。
type Renameable struct {
	Original    string
	Suggestions []Suggestion
	Locked      bool // 锚定符号 (UnityEngine/System/可读名), 不允许改名。
}

// Best 返回置信度最高的候选; 若无, 返回 nil。
func (r *Renameable) Best() *Suggestion {
	if len(r.Suggestions) == 0 {
		return nil
	}
	best := &r.Suggestions[0]
	for i := 1; i < len(r.Suggestions); i++ {
		if r.Suggestions[i].Confidence > best.Confidence {
			best = &r.Suggestions[i]
		}
	}
	return best
}

// Display 返回应使用的展示名称。
func (r *Renameable) Display() string {
	if r.Locked {
		return r.Original
	}
	if b := r.Best(); b != nil {
		return b.Name
	}
	return r.Original
}

// Suggest 添加一个候选; 若同名已存在则取置信度更高者。
func (r *Renameable) Suggest(name string, conf float64, reason string) {
	if r.Locked || name == "" || name == r.Original {
		return
	}
	for i := range r.Suggestions {
		if r.Suggestions[i].Name == name {
			if conf > r.Suggestions[i].Confidence {
				r.Suggestions[i].Confidence = conf
				r.Suggestions[i].Reason = reason
			}
			return
		}
	}
	r.Suggestions = append(r.Suggestions, Suggestion{Name: name, Confidence: conf, Reason: reason})
}

// Parameter 描述方法参数。
type Parameter struct {
	Name string
	Type string
}

// Field 描述类型字段。
type Field struct {
	Rename       Renameable
	Type         string
	IsStatic     bool
	IsPublic     bool
	IsSerialized bool
	Attributes   []string
	Offset       *int64
}

// Method 描述类型方法。
type Method struct {
	Rename        Renameable
	ReturnType    string
	Parameters    []Parameter
	IsStatic      bool
	IsPublic      bool
	IsVirtual     bool
	IsOverride    bool
	IsAbstract    bool
	IsConstructor bool
	Attributes    []string
	RVA           *int64
}

// Signature 返回 (返回类型, [参数类型, ...]) 形式的字符串签名。
func (m *Method) Signature() string {
	out := m.ReturnType + "("
	for i, p := range m.Parameters {
		if i > 0 {
			out += ", "
		}
		out += p.Type
	}
	return out + ")"
}

// Type 描述一个类/接口/枚举/值类型。
type Type struct {
	Rename        Renameable
	Namespace     string
	BaseType      string
	Interfaces    []string
	Fields        []Field
	Methods       []Method
	NestedTypes   []string
	DeclaringType string
	Attributes    []string
	IsEnum        bool
	IsInterface   bool
	IsAbstract    bool
	IsValueType   bool
	IsGeneric     bool
	Role          string // 推测的角色, 例如 "Player", "Weapon"。
}

// FullName 返回 namespace.<display name>。
func (t *Type) FullName() string {
	if t.Namespace == "" {
		return t.Rename.Display()
	}
	return t.Namespace + "." + t.Rename.Display()
}

// OriginalFullName 返回 namespace.<original name>。
func (t *Type) OriginalFullName() string {
	if t.Namespace == "" {
		return t.Rename.Original
	}
	return t.Namespace + "." + t.Rename.Original
}

// Module 是一次 dump 的全部类型集合。
type Module struct {
	Types      []*Type
	byOriginal map[string]*Type
}

// Index 重建按原始全名查找的索引。
func (m *Module) Index() {
	m.byOriginal = make(map[string]*Type, len(m.Types))
	for _, t := range m.Types {
		m.byOriginal[t.OriginalFullName()] = t
	}
}

// ByOriginalFullName 按原始全名查找类型。
func (m *Module) ByOriginalFullName(name string) *Type {
	if m.byOriginal == nil {
		m.Index()
	}
	return m.byOriginal[name]
}

// TransitiveSubclasses 返回继承链上含有 base 的所有类型。
func (m *Module) TransitiveSubclasses(base string) []*Type {
	if m.byOriginal == nil {
		m.Index()
	}
	var out []*Type
	for _, t := range m.Types {
		cur := t.BaseType
		seen := map[string]struct{}{}
		for cur != "" {
			if _, ok := seen[cur]; ok {
				break
			}
			seen[cur] = struct{}{}
			if cur == base {
				out = append(out, t)
				break
			}
			parent := m.byOriginal[cur]
			if parent == nil {
				break
			}
			cur = parent.BaseType
		}
	}
	return out
}
