// beautiful_dump - Unity IL2CPP 抗混淆通杀 dump 后处理
//
// 默认进入中文交互式向导, 一项一项询问参数, 回车采用默认值。
// 也可加 --non-interactive 走纯参数模式, 方便 CI / 脚本化调用。
package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type cliFlags struct {
	input          string
	outDump        string
	outMap         string
	nonInteractive bool
	noMessages     bool
	noSignatures   bool
	noPropagation  bool
}

func parseFlags(argv []string) (*cliFlags, error) {
	f := &cliFlags{}
	fs := flag.NewFlagSet("beautiful-dump", flag.ContinueOnError)
	fs.SetOutput(os.Stdout)
	fs.StringVar(&f.outDump, "out-dump", "", "重命名后的 dump.cs 输出路径")
	fs.StringVar(&f.outMap, "out-map", "", "符号映射 JSON 输出路径")
	fs.BoolVar(&f.nonInteractive, "non-interactive", false, "跳过交互向导, 完全使用命令行参数")
	fs.BoolVar(&f.noMessages, "no-messages", false, "跳过 Unity 消息函数恢复")
	fs.BoolVar(&f.noSignatures, "no-signatures", false, "跳过结构签名匹配")
	fs.BoolVar(&f.noPropagation, "no-propagation", false, "跳过引用传播")
	fs.Usage = func() {
		fmt.Fprintln(os.Stdout, "用法: beautiful-dump [选项] [<metadata.json>]")
		fmt.Fprintln(os.Stdout, "")
		fmt.Fprintln(os.Stdout, "默认进入中文交互式向导, 回车确认。")
		fmt.Fprintln(os.Stdout, "")
		fmt.Fprintln(os.Stdout, "选项:")
		fs.PrintDefaults()
	}
	if err := fs.Parse(argv); err != nil {
		return nil, err
	}
	rest := fs.Args()
	if len(rest) > 0 {
		f.input = rest[0]
	}
	return f, nil
}

func stem(path string) string {
	base := filepath.Base(path)
	if i := strings.LastIndex(base, "."); i > 0 {
		base = base[:i]
	}
	return base
}

func interactiveConfig(p *promptIO, f *cliFlags) (*PipelineOpts, error) {
	p.banner()
	p.info("接下来会逐项询问参数, 回车采用 [默认值]")
	p.info("支持 ~/ 路径, 相对路径会基于当前目录解析")
	p.info("中途 Ctrl+C 可随时退出")

	p.heading("第 1 步 / 共 4 步: 选择输入")
	defaultIn := f.input
	if defaultIn == "" {
		defaultIn = "metadata.json"
	}
	metadata := p.askPath("Il2CppInspector 导出的 metadata JSON 文件路径", defaultIn, true)

	p.heading("第 2 步 / 共 4 步: 选择输出")
	st := stem(metadata)
	defDump := f.outDump
	if defDump == "" {
		defDump = st + ".renamed.cs"
	}
	defMap := f.outMap
	if defMap == "" {
		defMap = st + ".symbols.json"
	}
	outDump := p.askPath("输出: 重命名后的 dump.cs", defDump, false)
	outMap := p.askPath("输出: 符号映射表 (JSON)", defMap, false)

	p.heading("第 3 步 / 共 4 步: 选择启用的恢复阶段")
	enableMessages := p.askYesNo("启用 Unity 消息函数恢复 (Update / OnTriggerEnter / ...)?", !f.noMessages)
	enableSigs := p.askYesNo("启用结构签名匹配 (Player / Weapon / Health / ...)?", !f.noSignatures)
	enableProp := p.askYesNo("启用引用传播 (字段类型 → 字段名)?", !f.noPropagation)

	p.heading("第 4 步 / 共 4 步: 确认")
	p.info(fmt.Sprintf("输入:        %s", metadata))
	p.info(fmt.Sprintf("输出 dump:    %s", outDump))
	p.info(fmt.Sprintf("输出 symbols: %s", outMap))
	onoff := func(b bool) string {
		if b {
			return "开"
		}
		return "关"
	}
	p.info(fmt.Sprintf("消息恢复:     %s", onoff(enableMessages)))
	p.info(fmt.Sprintf("签名匹配:     %s", onoff(enableSigs)))
	p.info(fmt.Sprintf("引用传播:     %s", onoff(enableProp)))
	if !p.askYesNo("以上设置是否正确?", true) {
		p.warn("已取消, 请重新运行")
		return nil, fmt.Errorf("用户取消")
	}

	return &PipelineOpts{
		MetadataPath:      metadata,
		OutDump:           outDump,
		OutMap:            outMap,
		EnableMessages:    enableMessages,
		EnableSignatures:  enableSigs,
		EnablePropagation: enableProp,
	}, nil
}

func nonInteractiveConfig(f *cliFlags) (*PipelineOpts, error) {
	if f.input == "" {
		return nil, fmt.Errorf("非交互模式下必须提供输入文件")
	}
	in := expandHome(f.input)
	if _, err := os.Stat(in); err != nil {
		return nil, fmt.Errorf("输入文件不存在: %s", in)
	}
	st := stem(in)
	outDump := f.outDump
	if outDump == "" {
		outDump = st + ".renamed.cs"
	}
	outMap := f.outMap
	if outMap == "" {
		outMap = st + ".symbols.json"
	}
	return &PipelineOpts{
		MetadataPath:      in,
		OutDump:           outDump,
		OutMap:            outMap,
		EnableMessages:    !f.noMessages,
		EnableSignatures:  !f.noSignatures,
		EnablePropagation: !f.noPropagation,
	}, nil
}

func mainExit() int {
	f, err := parseFlags(os.Args[1:])
	if err != nil {
		return 2
	}
	p := newPromptIO()
	var opts *PipelineOpts
	if f.nonInteractive {
		opts, err = nonInteractiveConfig(f)
	} else {
		opts, err = interactiveConfig(p, f)
	}
	if err != nil {
		p.warn(err.Error())
		return 1
	}
	opts.Log = p.info
	p.heading("开始处理")
	stats, err := Run(*opts)
	if err != nil {
		p.warn(err.Error())
		return 1
	}
	p.heading("统计")
	p.info(fmt.Sprintf("类型总数:           %d", stats.TypesTotal))
	p.info(fmt.Sprintf("识别为混淆的类型:   %d", stats.TypesObfuscated))
	p.info(fmt.Sprintf("消息函数恢复:       %d", stats.MessagesRecovered))
	p.info(fmt.Sprintf("签名命中:           %d", stats.SignatureHits))
	p.info(fmt.Sprintf("传播命中:           %d", stats.PropagationHits))
	p.info(fmt.Sprintf("最终重命名 (T/F/M): %d/%d/%d",
		stats.TypesRenamed, stats.FieldsRenamed, stats.MethodsRenamed))
	p.ok("完成")
	return 0
}

func main() {
	os.Exit(mainExit())
}
