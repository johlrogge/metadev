{ lib, ... }:
{
  options.metadev.polylith.enable = lib.mkOption {
    type = lib.types.bool;
    default = true;
    example = false;
    description = "Install cargo-polylith, its MCP server, the polylith agent and the architect's polylith tools. Disable for projects that are not polylith workspaces (e.g. a single crate).";
  };
}
