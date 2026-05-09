package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// promptIO 抽象 stdin/stdout 以便单测。
type promptIO struct {
	in  *bufio.Reader
	out io.Writer
	tty bool
}

func newPromptIO() *promptIO {
	fi, _ := os.Stdin.Stat()
	tty := (fi.Mode() & os.ModeCharDevice) != 0
	return &promptIO{
		in:  bufio.NewReader(os.Stdin),
		out: os.Stdout,
		tty: tty,
	}
}

const (
	tick  = "✔"
	cross = "✘"
	arrow = "›"
)

func (p *promptIO) printf(format string, args ...any) {
	fmt.Fprintf(p.out, format, args...)
}

func (p *promptIO) info(msg string)    { p.printf("  %s\n", msg) }
func (p *promptIO) heading(msg string) { p.printf("\n=== %s ===\n", msg) }
func (p *promptIO) ok(msg string)      { p.printf("  %s %s\n", tick, msg) }
func (p *promptIO) warn(msg string)    { p.printf("  %s %s\n", cross, msg) }

func expandHome(path string) string {
	if strings.HasPrefix(path, "~/") {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, path[2:])
		}
	}
	return path
}

// ask 询问字符串, 回车返回 default; 非 TTY 直接返回 default。
func (p *promptIO) ask(prompt, def string) string {
	suffix := ""
	if def != "" {
		suffix = fmt.Sprintf(" [默认: %s]", def)
	}
	if !p.tty {
		return def
	}
	for {
		p.printf("%s %s%s: ", arrow, prompt, suffix)
		line, err := p.in.ReadString('\n')
		if err != nil && line == "" {
			return def
		}
		v := strings.TrimSpace(line)
		if v == "" {
			if def != "" {
				return def
			}
			p.warn("此项必填, 请重新输入")
			continue
		}
		return v
	}
}

func (p *promptIO) askPath(prompt, def string, mustExist bool) string {
	for {
		v := expandHome(p.ask(prompt, def))
		if mustExist {
			if _, err := os.Stat(v); err != nil {
				p.warn(fmt.Sprintf("路径不存在: %s", v))
				continue
			}
		}
		return v
	}
}

func (p *promptIO) askYesNo(prompt string, def bool) bool {
	d := "Y/n"
	defStr := "Y"
	if !def {
		d = "y/N"
		defStr = "N"
	}
	for {
		v := strings.ToLower(p.ask(prompt+" ("+d+")", defStr))
		switch v {
		case "y", "yes", "是":
			return true
		case "n", "no", "否":
			return false
		}
		p.warn("请输入 y 或 n")
	}
}

// askChoice 列出枚举选项, 让用户选编号。
func (p *promptIO) askChoice(prompt string, items []choice, defIdx int) string {
	p.info(prompt)
	for i, it := range items {
		marker := "  "
		if i == defIdx {
			marker = " *"
		}
		p.printf("   %s [%d] %s  —  %s\n", marker, i+1, it.value, it.desc)
	}
	for {
		v := p.ask("请选择编号", strconv.Itoa(defIdx+1))
		idx, err := strconv.Atoi(v)
		if err != nil {
			p.warn("请输入数字")
			continue
		}
		if idx < 1 || idx > len(items) {
			p.warn(fmt.Sprintf("编号需在 1..%d 之间", len(items)))
			continue
		}
		return items[idx-1].value
	}
}

type choice struct {
	value string
	desc  string
}

func (p *promptIO) banner() {
	p.printf(
		"\n" +
			"+----------------------------------------------------------+\n" +
			"|   beautiful_dump  ·  Unity IL2CPP 抗混淆通杀 dump 后处理 |\n" +
			"+----------------------------------------------------------+\n",
	)
}
