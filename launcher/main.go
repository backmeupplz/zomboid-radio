// zomboid-radio: finds Project Zomboid, then runs the embedded patcher with the game's own bundled Java.
package main

import (
	"bufio"
	_ "embed"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
)

//go:embed patcher.jar
var patcherJar []byte

var stdin = bufio.NewReader(os.Stdin)

func main() {
	err := run()
	if err != nil {
		fmt.Println("\nERROR:", err)
	}
	fmt.Print("\nPress Enter to close...")
	stdin.ReadString('\n')
	if err != nil {
		os.Exit(1)
	}
}

func run() error {
	fmt.Println("Zomboid Radio patcher - adds WFPK Louisville (live) on 91.8 MHz")
	fmt.Println("Close Project Zomboid before continuing.")

	gameDir := ""
	if len(os.Args) > 1 {
		gameDir = os.Args[1] // folder dragged onto the executable
	} else {
		gameDir = findGameDir()
	}
	for {
		if jar := findJar(gameDir); jar != "" {
			return patch(gameDir, jar)
		}
		fmt.Print("\nCouldn't find Project Zomboid. In Steam: right-click the game > Manage > Browse local files,\n" +
			"copy that folder's path and paste it here: ")
		line, err := stdin.ReadString('\n')
		if err != nil {
			return fmt.Errorf("no game folder given")
		}
		gameDir = strings.Trim(strings.TrimSpace(line), `"'`)
	}
}

func patch(gameDir, jar string) error {
	fmt.Println("Game:", jar)
	java := findJava(gameDir)
	if java == "" {
		return fmt.Errorf("couldn't find the game's bundled Java under %s", gameDir)
	}

	tmp, err := os.CreateTemp("", "zomboid-radio-*.jar")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(patcherJar); err != nil {
		return err
	}
	tmp.Close()

	cmd := exec.Command(java, "-jar", tmp.Name(), jar)
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("patch failed (is the game still running?): %w", err)
	}
	return nil
}

func findGameDir() string {
	home, _ := os.UserHomeDir()
	var roots []string
	switch runtime.GOOS {
	case "windows":
		roots = []string{os.Getenv("ProgramFiles(x86)") + `\Steam`, os.Getenv("ProgramFiles") + `\Steam`}
	case "darwin":
		roots = []string{filepath.Join(home, "Library/Application Support/Steam")}
	default:
		roots = []string{
			filepath.Join(home, ".local/share/Steam"),
			filepath.Join(home, ".steam/steam"),
			filepath.Join(home, ".var/app/com.valvesoftware.Steam/.local/share/Steam"),
			filepath.Join(home, "snap/steam/common/.local/share/Steam"),
		}
	}

	libPath := regexp.MustCompile(`"path"\s+"([^"]+)"`)
	for _, root := range roots {
		libs := []string{root}
		if vdf, err := os.ReadFile(filepath.Join(root, "steamapps", "libraryfolders.vdf")); err == nil {
			for _, m := range libPath.FindAllStringSubmatch(string(vdf), -1) {
				libs = append(libs, strings.ReplaceAll(m[1], `\\`, `\`))
			}
		}
		for _, lib := range libs {
			dir := filepath.Join(lib, "steamapps", "common", "ProjectZomboid")
			if _, err := os.Stat(dir); err == nil {
				return dir
			}
		}
	}
	return ""
}

// find walks dir (skipping the huge game asset folders) and returns every file for which match is true.
func find(dir string, match func(path string) bool) []string {
	var found []string
	if dir == "" {
		return nil
	}
	filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() && (d.Name() == "media" || d.Name() == "Workshop") {
			return filepath.SkipDir
		}
		if !d.IsDir() && match(path) {
			found = append(found, path)
		}
		return nil
	})
	return found
}

func findJar(gameDir string) string {
	jars := find(gameDir, func(p string) bool { return filepath.Base(p) == "projectzomboid.jar" })
	if len(jars) == 0 {
		return ""
	}
	return jars[0]
}

// findJava returns the game's bundled java, preferring jre64 over any old 32-bit jre.
func findJava(gameDir string) string {
	name := "java"
	if runtime.GOOS == "windows" {
		name = "java.exe"
	}
	javas := find(gameDir, func(p string) bool {
		return filepath.Base(p) == name && filepath.Base(filepath.Dir(p)) == "bin"
	})
	for _, j := range javas {
		if strings.Contains(j, "jre64") {
			return j
		}
	}
	if len(javas) > 0 {
		return javas[0]
	}
	return ""
}
