{ lib, config, options, ... }:
let
  cfg = config.metadev.skills;

  # Build a claude.code.skills value from a source directory:
  #   <dir>/SKILL.md  -> content (body only; devenv generates the frontmatter)
  #   every other file under <dir> -> resources, keyed by its path relative to <dir>
  skillFromDir = dir: attrs:
    let
      root = toString dir;
      rel = f: lib.removePrefix "${root}/" (toString f);
      content = builtins.readFile (dir + "/SKILL.md");
      resourceFiles = lib.filter (f: rel f != "SKILL.md") (lib.filesystem.listFilesRecursive dir);
    in
    assert lib.assertMsg (!lib.hasPrefix "---" content)
      "${root}/SKILL.md must not contain YAML frontmatter; declare description etc. in Nix.";
    attrs // {
      inherit content;
      resources = lib.listToAttrs (map (f: lib.nameValuePair (rel f) { source = f; }) resourceFiles)
        // (attrs.resources or { });
    };

  shared = {
    conventional-commits = {
      description = "Conventional Commits specification. Defines commit message format, types, and rules.";
      whenToUse = "Before writing any git commit message.";
    };
    helix = {
      description = "Helix/Kakoune keymap expertise: selection-first model, modes, default keybindings, and conventions for designing TUI keymaps.";
      whenToUse = "When designing or reviewing keybindings for a TUI or modal tool, or answering Helix keymap questions.";
    };
    rust-architect = {
      description = "Rust architecture reference library: patterns, lifetimes, error handling, async/tokio, type-driven design, embedded/Embassy, testing, tooling, ECS, Polylith.";
      userInvocable = false;
    };
  };
in
{
  options.metadev.skills = lib.mapAttrs (name: _: {
    enable = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = "Install metadev's shared `${name}` skill into .claude/skills/${name}/.";
    };
  }) shared;

  config = {
    # Lets a consumer devenv.nix take `{ metadevLib, ... }:` and reuse the helper.
    _module.args.metadevLib = { inherit skillFromDir; };
  } // (
    # Older devenv inputs lack claude.code.skills; skip the skills instead of failing evaluation.
    if options.claude.code ? skills then {
      claude.code.skills = lib.mapAttrs (name: attrs:
        lib.mkIf cfg.${name}.enable
          (lib.mkDefault (skillFromDir (../skills + "/${name}") attrs))
      ) shared;
    } else lib.warn
      "metadev: this devenv has no claude.code.skills option, so the shared skills are not installed. Run `devenv update` to get them."
      { }
  );
}
