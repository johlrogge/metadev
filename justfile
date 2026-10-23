# metadev task recipes

# Align nixpkgs pins across ~/projects (creates chore/align-nixpkgs branches, never pushes)
align-nixpkgs *ARGS:
    bb tools/align-nixpkgs/align.bb run {{ARGS}}

# Show what align-nixpkgs would do; writes nothing
align-nixpkgs-dry *ARGS:
    bb tools/align-nixpkgs/align.bb run --dry-run {{ARGS}}

# Install and enable the weekly systemd user timer
align-nixpkgs-install-timer:
    bb tools/align-nixpkgs/align.bb install-timer

# Show timer state and the latest report
align-nixpkgs-status:
    bb tools/align-nixpkgs/align.bb status

# Unit tests for align-nixpkgs pure functions
align-nixpkgs-test:
    bb tools/align-nixpkgs/align_test.bb
