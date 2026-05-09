package main

import "strings"

// UnitySignatures 返回通用 Unity / 第三方组件的结构签名。
func UnitySignatures() []TypeSignature {
	return []TypeSignature{
		{
			Name:      "GameManager",
			Role:      "GameManager",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"self"}, Weight: 2.0},
			},
			Threshold:  2.0,
			Confidence: 0.6,
			Extra: func(t *Type) float64 {
				for _, f := range t.Fields {
					if f.IsStatic && strings.HasSuffix(f.Type, t.Rename.Original) {
						return 1.5
					}
				}
				return 0
			},
		},
		{
			Name:      "UIButtonHandler",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.UI.Button"}, Weight: 2.0, Required: true, SuggestName: "button"},
			},
			Threshold:  2.0,
			Confidence: 0.7,
		},
		{
			Name:      "AudioController",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.AudioSource"}, Weight: 2.0, Required: true, SuggestName: "audioSource"},
				{Types: []string{"UnityEngine.AudioClip", "UnityEngine.AudioClip[]"}, Weight: 1.0, SuggestName: "clip"},
			},
			Threshold:  2.0,
			Confidence: 0.7,
		},
		{
			Name:      "AnimatorController",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.Animator"}, Weight: 2.0, Required: true, SuggestName: "animator"},
			},
			Threshold:  2.0,
			Confidence: 0.6,
		},
		{
			Name:      "ObjectPool",
			BaseTypes: []string{"UnityEngine.MonoBehaviour", "System.Object"},
			FieldRules: []FieldRule{
				{
					Types: []string{
						"System.Collections.Generic.Queue`1<UnityEngine.GameObject>",
						"System.Collections.Generic.Stack`1<UnityEngine.GameObject>",
						"System.Collections.Generic.List`1<UnityEngine.GameObject>",
					},
					Weight: 2.0, Required: true, SuggestName: "pool",
				},
				{Types: []string{"UnityEngine.GameObject"}, Weight: 1.0, SuggestName: "prefab"},
			},
			Threshold:  2.0,
			Confidence: 0.7,
		},
		{
			Name:      "SceneLoader",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			MethodRules: []MethodRule{
				{ReturnType: "System.Void", ParamTypes: []string{"System.String"}, Weight: 1.5, SuggestName: "LoadScene"},
			},
			FieldRules: []FieldRule{
				{Types: []string{"System.String", "System.String[]"}, Weight: 0.5, SuggestName: "sceneName"},
				{Types: []string{"UnityEngine.UI.Slider"}, Weight: 1.5, SuggestName: "progressBar"},
			},
			Threshold:  2.5,
			Confidence: 0.55,
		},
		{
			Name:       "PhotonNetworkBehaviour",
			BaseTypes:  []string{"Photon.Pun.MonoBehaviourPunCallbacks", "Photon.Pun.MonoBehaviourPun"},
			Threshold:  0.0,
			Confidence: 0.95,
		},
		{
			Name:       "MirrorNetworkBehaviour",
			BaseTypes:  []string{"Mirror.NetworkBehaviour"},
			Threshold:  0.0,
			Confidence: 0.95,
		},
	}
}
