package main

// FPSSignatures 返回 FPS 游戏常见的核心类签名。
func FPSSignatures() []TypeSignature {
	return []TypeSignature{
		{
			Name:      "PlayerController",
			Role:      "Player",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.CharacterController"}, Weight: 3.0, Required: true, SuggestName: "characterController"},
				{Types: []string{"UnityEngine.Camera"}, Weight: 1.0, SuggestName: "playerCamera"},
				{Types: []string{"UnityEngine.Transform"}, Weight: 0.5},
				{Types: []string{"System.Single"}, Weight: 0.5, SuggestName: "moveSpeed"},
			},
			Threshold:  3.0,
			Confidence: 0.85,
		},
		{
			Name:      "RigidbodyPlayer",
			Role:      "Player",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.Rigidbody"}, Weight: 2.5, Required: true, SuggestName: "body"},
				{Types: []string{"UnityEngine.Camera"}, Weight: 1.0, Required: true, SuggestName: "playerCamera"},
				{Types: []string{"System.Single"}, Weight: 0.3, SuggestName: "jumpForce"},
			},
			Threshold:  3.0,
			Confidence: 0.8,
		},
		{
			Name:      "MouseLook",
			Role:      "Camera",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.Transform"}, Weight: 1.5, Required: true, SuggestName: "cameraTransform"},
				{Types: []string{"System.Single"}, Weight: 0.5, SuggestName: "sensitivity"},
			},
			MethodRules: []MethodRule{
				{ReturnType: "System.Void", ParamTypes: []string{}, Weight: 0.5},
			},
			Threshold:  2.5,
			Confidence: 0.55,
		},
		{
			Name:      "Health",
			Role:      "Health",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			MethodRules: []MethodRule{
				{ReturnType: "System.Void", ParamTypes: []string{"System.Single"}, Weight: 2.0, Required: true, SuggestName: "TakeDamage"},
				{ReturnType: "System.Void", ParamTypes: []string{}, Weight: 1.0, SuggestName: "Die"},
			},
			FieldRules: []FieldRule{
				{Types: []string{"System.Single"}, Weight: 1.0, SuggestName: "currentHealth"},
			},
			Threshold:  3.0,
			Confidence: 0.8,
		},
		{
			Name:      "Weapon",
			Role:      "Weapon",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.GameObject"}, Weight: 0.5, SuggestName: "muzzleFlash"},
				{Types: []string{"UnityEngine.ParticleSystem"}, Weight: 0.5, SuggestName: "muzzleParticles"},
				{Types: []string{"UnityEngine.AudioSource"}, Weight: 0.5, SuggestName: "audioSource"},
				{Types: []string{"UnityEngine.AudioClip"}, Weight: 0.5, SuggestName: "fireSound"},
				{Types: []string{"UnityEngine.Transform"}, Weight: 0.5, SuggestName: "muzzlePoint"},
				{Types: []string{"System.Single"}, Weight: 1.0, SuggestName: "damage"},
				{Types: []string{"System.Int32"}, Weight: 0.5, SuggestName: "ammo"},
			},
			MethodRules: []MethodRule{
				{ReturnType: "System.Void", ParamTypes: []string{}, Weight: 1.5, SuggestName: "Fire"},
				{ReturnType: "System.Void", ParamTypes: []string{}, Weight: 1.0, SuggestName: "Reload"},
			},
			Threshold:  4.0,
			Confidence: 0.8,
		},
		{
			Name:      "Projectile",
			Role:      "Projectile",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.Rigidbody"}, Weight: 1.0, SuggestName: "body"},
				{Types: []string{"System.Single"}, Weight: 0.5, SuggestName: "speed"},
				{Types: []string{"System.Single"}, Weight: 0.5, SuggestName: "damage"},
				{Types: []string{"UnityEngine.GameObject"}, Weight: 0.5, SuggestName: "impactEffect"},
			},
			MethodRules: []MethodRule{
				{ReturnType: "System.Void", ParamTypes: []string{"UnityEngine.Collider"}, Weight: 2.0, SuggestName: "OnTriggerEnter"},
			},
			Threshold:  3.0,
			Confidence: 0.7,
		},
		{
			Name:      "Recoil",
			Role:      "Recoil",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.Vector3"}, Weight: 1.5, Required: true, SuggestName: "recoilAmount"},
				{Types: []string{"System.Single"}, Weight: 0.5, SuggestName: "recoilSpeed"},
			},
			Threshold:  2.0,
			Confidence: 0.55,
		},
		{
			Name:      "AmmoHUD",
			Role:      "HUD",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"TMPro.TextMeshProUGUI", "UnityEngine.UI.Text"}, Weight: 2.0, Required: true, SuggestName: "ammoText"},
				{Types: []string{"UnityEngine.UI.Image"}, Weight: 0.5, SuggestName: "reloadIcon"},
			},
			Threshold:  2.0,
			Confidence: 0.6,
		},
		{
			Name:      "EnemyAI",
			Role:      "Enemy",
			BaseTypes: []string{"UnityEngine.MonoBehaviour"},
			FieldRules: []FieldRule{
				{Types: []string{"UnityEngine.AI.NavMeshAgent"}, Weight: 3.0, Required: true, SuggestName: "agent"},
				{Types: []string{"UnityEngine.Transform"}, Weight: 0.5, SuggestName: "target"},
				{Types: []string{"System.Single"}, Weight: 0.3, SuggestName: "attackRange"},
			},
			Threshold:  3.0,
			Confidence: 0.85,
		},
	}
}
