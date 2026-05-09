package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const fixturePath = "examples/obfuscated_fps.json"

func loadFixture(t *testing.T) *Module {
	t.Helper()
	mod, err := LoadModule(fixturePath)
	if err != nil {
		t.Fatalf("load fixture: %v", err)
	}
	return mod
}

func findType(mod *Module, name string) *Type {
	for _, t := range mod.Types {
		if t.Rename.Original == name {
			return t
		}
	}
	return nil
}

func TestObfuscationClassifier(t *testing.T) {
	cases := []struct {
		name string
		want bool
	}{
		{"Aa", true}, {"Il", true}, {"I1", true}, {"IlIl", true},
		{"PlayerController", false}, {"Update", false}, {"OnTriggerEnter", false},
		{".ctor", false}, {"ID", false}, {"X", false},
	}
	for _, c := range cases {
		got := LooksObfuscated(c.name)
		if got != c.want {
			t.Errorf("LooksObfuscated(%q) = %v, 期望 %v", c.name, got, c.want)
		}
	}
}

func TestAnchorPassLocksUnityAndCleanNames(t *testing.T) {
	mod := loadFixture(t)
	LockAnchors(mod)

	mb := findType(mod, "MonoBehaviour")
	if mb == nil || !mb.Rename.Locked {
		t.Errorf("UnityEngine.MonoBehaviour 应该被锁定")
	}
	cc := findType(mod, "CharacterController")
	if cc == nil || !cc.Rename.Locked {
		t.Errorf("UnityEngine.CharacterController 应该被锁定")
	}
	good := findType(mod, "GoodLevelLoader")
	if good == nil || !good.Rename.Locked {
		t.Errorf("GoodLevelLoader (干净命名) 应该被锁定")
	}
	aa := findType(mod, "Aa")
	if aa == nil || aa.Rename.Locked {
		t.Errorf("Aa 不应被锁定 (是混淆名)")
	}
}

func TestFullPipelineRecoversFPSClasses(t *testing.T) {
	mod := loadFixture(t)
	LockAnchors(mod)
	RecoverMessages(mod)
	ApplySignatures(mod, AllSignatures())
	Propagate(mod, 3)

	aa := findType(mod, "Aa")
	if b := aa.Rename.Best(); b == nil || b.Name != "PlayerController" {
		t.Errorf("Aa 应识别为 PlayerController, 实际: %+v", aa.Rename.Best())
	}
	for fi := range aa.Fields {
		if aa.Fields[fi].Type == "UnityEngine.CharacterController" {
			b := aa.Fields[fi].Rename.Best()
			if b == nil || b.Name != "characterController" {
				t.Errorf("CharacterController 字段应被命名为 characterController, 实际 %+v", b)
			}
		}
	}

	bb := findType(mod, "Bb")
	if b := bb.Rename.Best(); b == nil || b.Name != "Weapon" {
		t.Errorf("Bb 应识别为 Weapon, 实际: %+v", bb.Rename.Best())
	}

	cc := findType(mod, "Cc")
	if b := cc.Rename.Best(); b == nil || b.Name != "Health" {
		t.Errorf("Cc 应识别为 Health, 实际: %+v", cc.Rename.Best())
	}
	var qlMethod *Method
	for mi := range cc.Methods {
		if cc.Methods[mi].Rename.Original == "ql" {
			qlMethod = &cc.Methods[mi]
		}
	}
	if qlMethod == nil {
		t.Fatalf("Cc.ql 方法不见了")
	}
	if b := qlMethod.Rename.Best(); b == nil || b.Name != "TakeDamage" {
		t.Errorf("Cc.ql 应识别为 TakeDamage, 实际: %+v", b)
	}

	dd := findType(mod, "Dd")
	if b := dd.Rename.Best(); b == nil || b.Name != "EnemyAI" {
		t.Errorf("Dd 应识别为 EnemyAI, 实际: %+v", dd.Rename.Best())
	}
}

func TestMessageRecoveryFindsCollider(t *testing.T) {
	mod := loadFixture(t)
	LockAnchors(mod)
	n := RecoverMessages(mod)
	if n < 1 {
		t.Errorf("应至少找到 1 条 Unity 消息函数, 实际 %d", n)
	}
	aa := findType(mod, "Aa")
	var lI1 *Method
	for mi := range aa.Methods {
		if aa.Methods[mi].Rename.Original == "lI1" {
			lI1 = &aa.Methods[mi]
		}
	}
	if lI1 == nil {
		t.Fatalf("Aa.lI1 方法不见了")
	}
	if b := lI1.Rename.Best(); b == nil || b.Name != "OnTriggerEnter" {
		t.Errorf("Aa.lI1 应识别为 OnTriggerEnter, 实际: %+v", b)
	}
}

func TestRenderOutputs(t *testing.T) {
	mod := loadFixture(t)
	LockAnchors(mod)
	RecoverMessages(mod)
	ApplySignatures(mod, AllSignatures())
	Propagate(mod, 3)

	cs := RenderDumpCS(mod)
	for _, want := range []string{"PlayerController", "Weapon", "Health", "EnemyAI", "GoodLevelLoader", "/* obf: Aa"} {
		if !strings.Contains(cs, want) {
			t.Errorf("dump.cs 缺少 %q", want)
		}
	}
	tmp := t.TempDir()
	if err := WriteDumpCS(mod, filepath.Join(tmp, "x.cs")); err != nil {
		t.Errorf("写 dump.cs: %v", err)
	}
	mapPath := filepath.Join(tmp, "x.json")
	if err := WriteSymbolMap(mod, mapPath); err != nil {
		t.Errorf("写 symbol map: %v", err)
	}
	data, err := os.ReadFile(mapPath)
	if err != nil {
		t.Fatalf("读 symbol map: %v", err)
	}
	for _, want := range []string{`"original": "Aa"`, `"renamed": "PlayerController"`} {
		if !strings.Contains(string(data), want) {
			t.Errorf("symbol map 缺少 %q", want)
		}
	}
}

func TestCleanTypeNotRenamed(t *testing.T) {
	mod := loadFixture(t)
	LockAnchors(mod)
	ApplySignatures(mod, AllSignatures())
	g := findType(mod, "GoodLevelLoader")
	if g == nil || !g.Rename.Locked {
		t.Errorf("GoodLevelLoader 应该被锁定")
	}
	if g.Rename.Best() != nil {
		t.Errorf("锁定的类型不应再被重命名")
	}
}
