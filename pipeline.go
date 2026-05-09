package main

import "fmt"

// PipelineOpts 控制 Run 的行为。
type PipelineOpts struct {
	MetadataPath      string
	OutDump           string
	OutMap            string
	EnableMessages    bool
	EnableSignatures  bool
	EnablePropagation bool
	ExtraSignatures   []TypeSignature
	Log               func(string)
}

// Stats 是一次运行的统计结果。
type Stats struct {
	TypesTotal         int
	TypesObfuscated    int
	FieldsObfuscated   int
	MethodsObfuscated  int
	MessagesRecovered  int
	SignatureHits      int
	PropagationHits    int
	TypesRenamed       int
	FieldsRenamed      int
	MethodsRenamed     int
}

func countObfuscated(mod *Module) (typesO, fieldsO, methodsO int) {
	for _, t := range mod.Types {
		if !t.Rename.Locked {
			typesO++
		}
		for fi := range t.Fields {
			if !t.Fields[fi].Rename.Locked {
				fieldsO++
			}
		}
		for mi := range t.Methods {
			if !t.Methods[mi].Rename.Locked {
				methodsO++
			}
		}
	}
	return
}

func countRenamed(mod *Module) (typesR, fieldsR, methodsR int) {
	for _, t := range mod.Types {
		if t.Rename.Best() != nil && !t.Rename.Locked {
			typesR++
		}
		for fi := range t.Fields {
			if t.Fields[fi].Rename.Best() != nil {
				fieldsR++
			}
		}
		for mi := range t.Methods {
			if t.Methods[mi].Rename.Best() != nil {
				methodsR++
			}
		}
	}
	return
}

// Run 执行整条流水线。
func Run(opts PipelineOpts) (*Stats, error) {
	logf := func(s string) {
		if opts.Log != nil {
			opts.Log(s)
		}
	}

	logf(fmt.Sprintf("[1/6] 读取 metadata: %s", opts.MetadataPath))
	mod, err := LoadModule(opts.MetadataPath)
	if err != nil {
		return nil, err
	}
	logf(fmt.Sprintf("      共载入 %d 个类型", len(mod.Types)))

	logf("[2/6] 锚定: 标记非混淆符号 (UnityEngine/System/可读名称)")
	LockAnchors(mod)
	tO, fO, mO := countObfuscated(mod)
	logf(fmt.Sprintf("      候选混淆: 类型 %d / 字段 %d / 方法 %d", tO, fO, mO))

	stats := &Stats{
		TypesTotal:        len(mod.Types),
		TypesObfuscated:   tO,
		FieldsObfuscated:  fO,
		MethodsObfuscated: mO,
	}

	if opts.EnableMessages {
		logf("[3/6] Unity 消息函数恢复 (Update/OnTriggerEnter/...)")
		n := RecoverMessages(mod)
		stats.MessagesRecovered = n
		logf(fmt.Sprintf("      建议数: %d", n))
	}

	if opts.EnableSignatures {
		sigs := append(AllSignatures(), opts.ExtraSignatures...)
		logf(fmt.Sprintf("[4/6] 结构签名匹配 (%d 条规则)", len(sigs)))
		n := ApplySignatures(mod, sigs)
		stats.SignatureHits = n
		logf(fmt.Sprintf("      命中建议数: %d", n))
	}

	if opts.EnablePropagation {
		logf("[5/6] 引用传播 (字段类型 → 字段名, 角色 → 方法名)")
		n := Propagate(mod, 3)
		stats.PropagationHits = n
		logf(fmt.Sprintf("      传播建议数: %d", n))
	}

	tR, fR, mR := countRenamed(mod)
	stats.TypesRenamed = tR
	stats.FieldsRenamed = fR
	stats.MethodsRenamed = mR

	logf("[6/6] 输出")
	if opts.OutDump != "" {
		if err := WriteDumpCS(mod, opts.OutDump); err != nil {
			return stats, fmt.Errorf("写 dump.cs 失败: %w", err)
		}
		logf(fmt.Sprintf("      重命名 dump.cs: %s", opts.OutDump))
	}
	if opts.OutMap != "" {
		if err := WriteSymbolMap(mod, opts.OutMap); err != nil {
			return stats, fmt.Errorf("写 symbol map 失败: %w", err)
		}
		logf(fmt.Sprintf("      符号映射 JSON: %s", opts.OutMap))
	}
	logf(fmt.Sprintf("\n完成: 共重命名 类型 %d / 字段 %d / 方法 %d", tR, fR, mR))
	return stats, nil
}
