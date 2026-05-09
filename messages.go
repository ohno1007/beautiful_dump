package main

import "strings"

// unityMessage 描述一个 Unity 引擎反射调用的回调。
type unityMessage struct {
	Name   string
	Return string
	Params []string
}

// 由引擎按字符串名反射调用的 MonoBehaviour 消息函数列表。
// 仅对参数中含有 UnityEngine.* 类型的消息进行无歧义重命名 — 仅基于
// (返回类型, 参数类型) 签名的全原语签名 (例如 OnJointBreak(float))
// 太容易和用户代码 (TakeDamage(float)) 撞车。
var unityMessages = []unityMessage{
	{"Awake", "System.Void", nil},
	{"Start", "System.Void", nil},
	{"OnEnable", "System.Void", nil},
	{"OnDisable", "System.Void", nil},
	{"OnDestroy", "System.Void", nil},
	{"Reset", "System.Void", nil},
	{"OnValidate", "System.Void", nil},
	{"Update", "System.Void", nil},
	{"LateUpdate", "System.Void", nil},
	{"FixedUpdate", "System.Void", nil},
	{"OnGUI", "System.Void", nil},
	{"OnApplicationPause", "System.Void", []string{"System.Boolean"}},
	{"OnApplicationFocus", "System.Void", []string{"System.Boolean"}},
	{"OnApplicationQuit", "System.Void", nil},
	{"OnTriggerEnter", "System.Void", []string{"UnityEngine.Collider"}},
	{"OnTriggerStay", "System.Void", []string{"UnityEngine.Collider"}},
	{"OnTriggerExit", "System.Void", []string{"UnityEngine.Collider"}},
	{"OnTriggerEnter2D", "System.Void", []string{"UnityEngine.Collider2D"}},
	{"OnTriggerStay2D", "System.Void", []string{"UnityEngine.Collider2D"}},
	{"OnTriggerExit2D", "System.Void", []string{"UnityEngine.Collider2D"}},
	{"OnCollisionEnter", "System.Void", []string{"UnityEngine.Collision"}},
	{"OnCollisionStay", "System.Void", []string{"UnityEngine.Collision"}},
	{"OnCollisionExit", "System.Void", []string{"UnityEngine.Collision"}},
	{"OnCollisionEnter2D", "System.Void", []string{"UnityEngine.Collision2D"}},
	{"OnCollisionStay2D", "System.Void", []string{"UnityEngine.Collision2D"}},
	{"OnCollisionExit2D", "System.Void", []string{"UnityEngine.Collision2D"}},
	{"OnControllerColliderHit", "System.Void", []string{"UnityEngine.ControllerColliderHit"}},
	{"OnMouseDown", "System.Void", nil},
	{"OnMouseUp", "System.Void", nil},
	{"OnMouseEnter", "System.Void", nil},
	{"OnMouseExit", "System.Void", nil},
	{"OnMouseOver", "System.Void", nil},
	{"OnMouseDrag", "System.Void", nil},
	{"OnMouseUpAsButton", "System.Void", nil},
	{"OnBecameVisible", "System.Void", nil},
	{"OnBecameInvisible", "System.Void", nil},
	{"OnPreCull", "System.Void", nil},
	{"OnPreRender", "System.Void", nil},
	{"OnPostRender", "System.Void", nil},
	{"OnRenderObject", "System.Void", nil},
	{"OnRenderImage", "System.Void", []string{"UnityEngine.RenderTexture", "UnityEngine.RenderTexture"}},
	{"OnDrawGizmos", "System.Void", nil},
	{"OnDrawGizmosSelected", "System.Void", nil},
	{"OnAnimatorIK", "System.Void", []string{"System.Int32"}},
	{"OnAnimatorMove", "System.Void", nil},
	{"OnLevelWasLoaded", "System.Void", []string{"System.Int32"}},
	{"OnAudioFilterRead", "System.Void", []string{"System.Single[]", "System.Int32"}},
	{"OnParticleCollision", "System.Void", []string{"UnityEngine.GameObject"}},
	{"OnParticleTrigger", "System.Void", nil},
	{"OnJointBreak", "System.Void", []string{"System.Single"}},
	{"OnJointBreak2D", "System.Void", []string{"UnityEngine.Joint2D"}},
	{"OnTransformChildrenChanged", "System.Void", nil},
	{"OnTransformParentChanged", "System.Void", nil},
}

const monoBehaviourFQN = "UnityEngine.MonoBehaviour"

func sigEqual(m *Method, ret string, params []string) bool {
	if m.ReturnType != ret {
		return false
	}
	if len(m.Parameters) != len(params) {
		return false
	}
	for i, p := range params {
		if m.Parameters[i].Type != p {
			return false
		}
	}
	return true
}

// 拒绝把全原语签名映射到消息上 — 太容易和用户代码冲突。
func unitySpecific(params []string) bool {
	for _, p := range params {
		if strings.HasPrefix(p, "UnityEngine.") {
			return true
		}
	}
	return false
}

func candidatesFor(m *Method) []string {
	var out []string
	for _, msg := range unityMessages {
		if !sigEqual(m, msg.Return, msg.Params) {
			continue
		}
		if len(msg.Params) > 0 && !unitySpecific(msg.Params) {
			continue
		}
		out = append(out, msg.Name)
	}
	return out
}

// RecoverMessages 在所有 MonoBehaviour 子类上提议 Unity 消息函数名。
// 返回提议数量。
func RecoverMessages(mod *Module) int {
	subs := mod.TransitiveSubclasses(monoBehaviourFQN)
	suggestions := 0

	for _, t := range subs {
		used := map[string]struct{}{}
		// 已锁定的同名占位 (例如 obfuscator 漏过的 Update) 也要登记进 used,
		// 避免我们提议同名新方法。
		for mi := range t.Methods {
			if t.Methods[mi].Rename.Locked {
				used[t.Methods[mi].Rename.Original] = struct{}{}
			}
		}

		for mi := range t.Methods {
			m := &t.Methods[mi]
			if m.Rename.Locked {
				continue
			}
			cands := candidatesFor(m)
			// 过滤掉已被同型兄弟方法占用的名字。
			filtered := cands[:0]
			for _, c := range cands {
				if _, dup := used[c]; !dup {
					filtered = append(filtered, c)
				}
			}
			if len(filtered) == 0 {
				continue
			}
			if len(filtered) == 1 {
				m.Rename.Suggest(filtered[0], 0.95, "Unity 消息函数签名匹配")
				used[filtered[0]] = struct{}{}
				suggestions++
				continue
			}
			// 多候选 — 仅在 OnTrigger*/OnCollision* 同族内挑 Enter 变体。
			allTrigCol := true
			for _, c := range filtered {
				if !strings.HasPrefix(c, "OnTrigger") && !strings.HasPrefix(c, "OnCollision") {
					allTrigCol = false
					break
				}
			}
			if !allTrigCol {
				continue
			}
			pick := ""
			for _, c := range filtered {
				if strings.HasSuffix(c, "Enter") {
					pick = c
					break
				}
			}
			if pick != "" {
				m.Rename.Suggest(pick, 0.65, "Unity 消息签名 (同族歧义, 选 Enter 变体)")
				used[pick] = struct{}{}
				suggestions++
			}
		}
	}
	return suggestions
}
