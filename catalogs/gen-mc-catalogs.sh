#!/usr/bin/env bash
set -euo pipefail

AMBER_DIR=~/code/mods/amber
BASE_URL="https://echo.iamkaf.com"

VERSIONS=(
  1.20.1 1.20.2 1.20.3 1.20.4 1.20.5 1.20.6
  1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9
)

# Constants (keep aligned with existing catalogs unless we have a reason to change)
MIXIN_VERSION="0.8.5"
MIXINEXTRAS_VERSION="0.4.1"
GSON_VERSION="2.10.1"
MODPUBLISHER_PLUGIN="2.1.6"
MIXIN_PLUGIN="0.7-SNAPSHOT"

json_escape() { jq -Rn --arg s "$1" '$s'; }

fetch_json() {
  local url="$1"
  curl -fsSL "$url"
}

echo_dep() {
  local mc="$1" name="$2"
  fetch_json "$BASE_URL/api/versions/dependencies/$mc" | jq -r --arg name "$name" '.data.dependencies[] | select(.name==$name) | .version' | head -n 1
}

echo_project_version() {
  local mc="$1" project="$2" loader="$3"
  fetch_json "$BASE_URL/api/projects/compatibility?projects=$project&versions=$mc" | jq -r --arg p "$project" --arg mc "$mc" --arg l "$loader" '.data[$p][$mc][$l]'
}

# Read a key from a properties file blob (supports both key=value and key = value)
prop_get() {
  local blob="$1" key="$2"
  printf '%s\n' "$blob" | sed -nE "s/^\s*${key}\s*=\s*(.*)\s*$/\1/p" | head -n 1
}

for mc in "${VERSIONS[@]}"; do
  echo "== Generating mc-$mc =="

  # Try to source values from Amber if a branch exists
  amber_branch=""
  if git -C "$AMBER_DIR" rev-parse -q --verify "origin/multiloader/$mc" >/dev/null 2>&1; then
    amber_branch="origin/multiloader/$mc"
  elif git -C "$AMBER_DIR" rev-parse -q --verify "origin/$mc" >/dev/null 2>&1; then
    amber_branch="origin/$mc"
  fi

  amber_props=""
  if [[ -n "$amber_branch" ]]; then
    amber_props=$(git -C "$AMBER_DIR" show "$amber_branch":gradle.properties)
  fi

  # Core versions (Echo as baseline)
  forge_ver=$(echo_dep "$mc" "forge")
  neoforge_ver=$(echo_dep "$mc" "neoforge")
  fabric_loader_ver=$(echo_dep "$mc" "fabric-loader")
  fabric_api_ver=$(echo_dep "$mc" "fabric-api")
  parchment_ver=$(echo_dep "$mc" "parchment")
  neoform_ver=$(echo_dep "$mc" "neoform")
  moddev_ver=$(echo_dep "$mc" "moddev-gradle")
  forgegradle_ver=$(echo_dep "$mc" "forgegradle")
  loom_ver=$(echo_dep "$mc" "loom")

  # Fill from Amber where available (preferred)
  amber_version=""
  modmenu_ver=""
  java_ver=""
  parchment_mc="$mc"

  if [[ -n "$amber_branch" ]]; then
    # new schema
    amber_version=$(prop_get "$amber_props" "version")
    java_ver=$(prop_get "$amber_props" "java_version")
    parchment_mc=$(prop_get "$amber_props" "parchment_minecraft" || true)

    # old schema
    if [[ -z "$amber_version" ]]; then
      amber_version=$(prop_get "$amber_props" "mod_version")
    fi
    if [[ -z "$java_ver" ]]; then
      # infer java for old branches
      if [[ "$mc" == 1.21* ]]; then java_ver="21"; else java_ver="17"; fi
    fi

    # fabric api / loader
    fa=$(prop_get "$amber_props" "fabric_version" || true)
    if [[ -z "$fa" ]]; then
      fa=$(prop_get "$amber_props" "fabric_api_version" || true)
    fi
    if [[ -n "$fa" ]]; then fabric_api_ver="$fa"; fi

    fl=$(prop_get "$amber_props" "fabric_loader_version" || true)
    if [[ -n "$fl" ]]; then fabric_loader_ver="$fl"; fi

    mm=$(prop_get "$amber_props" "mod_menu_version" || true)
    if [[ -n "$mm" ]]; then modmenu_ver="$mm"; fi

    # forge/neoforge
    fv=$(prop_get "$amber_props" "forge_version" || true)
    if [[ -n "$fv" ]]; then
      # Some old branches store forge as "1.20.1-47.3.3"; trim mc prefix if present
      fv=${fv#${mc}-}
      forge_ver="$fv"
    fi

    nv=$(prop_get "$amber_props" "neoforge_version" || true)
    if [[ -n "$nv" ]]; then neoforge_ver="$nv"; fi

    # parchment (old branch format: "1.20.1:2023.09.03")
    pv=$(prop_get "$amber_props" "parchment_version" || true)
    if [[ -n "$pv" ]]; then
      if [[ "$pv" == *":"* ]]; then
        parchment_mc=${pv%%:*}
        parchment_ver=${pv##*:}
      else
        parchment_ver="$pv"
      fi
    fi

    # neoform (new schema)
    nf=$(prop_get "$amber_props" "neo_form_version" || true)
    if [[ -n "$nf" ]]; then neoform_ver="$nf"; fi
  else
    # no amber data → infer java
    if [[ "$mc" == 1.21* ]]; then java_ver="21"; else java_ver="17"; fi
  fi

  # If modmenu/amber missing, use Echo compatibility
  if [[ -z "$modmenu_ver" || "$modmenu_ver" == "null" ]]; then
    modmenu_ver=$(echo_project_version "$mc" "modmenu" "fabric")
  fi
  if [[ -z "$amber_version" || "$amber_version" == "null" ]]; then
    # Prefer fabric or neoforge; if both null, keep empty
    av=$(echo_project_version "$mc" "amber" "fabric")
    if [[ "$av" == "null" ]]; then av=$(echo_project_version "$mc" "amber" "neoforge"); fi
    if [[ "$av" != "null" ]]; then amber_version="$av"; fi
  fi

  # Normalize "N/A" from Echo to empty
  [[ "$neoforge_ver" == "N/A" ]] && neoforge_ver=""
  [[ "$neoform_ver" == "N/A" ]] && neoform_ver=""
  [[ "$moddev_ver" == "N/A" ]] && moddev_ver=""

  # Create module structure
  dir="mc-$mc"
  mkdir -p "$dir/gradle"

  cat > "$dir/build.gradle" <<EOF
plugins {
    id("version-catalog")
    id("java-platform")
    id("maven-publish")
}

group = "com.iamkaf.platform"
version = "$mc-SNAPSHOT"

javaPlatform {
    allowDependencies()
}

catalog {
    versionCatalog {
        from(files("gradle/libs.versions.toml"))
    }
}

publishing {
    publications {
        maven(MavenPublication) {
            from components.versionCatalog
        }
    }
    repositories {
        maven {
            name = 'KafMaven'
            url = project.version.endsWith('-SNAPSHOT')
                ? 'https://z.kaf.sh/snapshots'
                : 'https://z.kaf.sh/releases'
            credentials {
                username = project.findProperty('maven.kaf.username') ?: System.getenv('MAVEN_PUBLISH_USERNAME')
                password = project.findProperty('maven.kaf.password') ?: System.getenv('MAVEN_PUBLISH_PASSWORD')
            }
        }
    }
}
EOF

  # Build libs.versions.toml
  {
    echo "[versions]"
    echo "# Core Versions"
    echo "minecraft = \"$mc\""
    echo "java = \"$java_ver\""

    if [[ -n "$neoform_ver" ]]; then
      echo "neoform = \"$neoform_ver\""
      echo
      echo "# Parchment"
      echo "parchment-minecraft = \"$parchment_mc\""
      echo "parchment = \"$parchment_ver\""
    else
      echo
      echo "# Parchment"
      echo "parchment-minecraft = \"$parchment_mc\""
      echo "parchment = \"$parchment_ver\""
    fi

    echo
    echo "# Fabric"
    echo "fabric-loader = \"$fabric_loader_ver\""
    echo "fabric-api = \"$fabric_api_ver\""
    if [[ -n "$modmenu_ver" && "$modmenu_ver" != "null" ]]; then
      echo "modmenu = \"$modmenu_ver\""
    fi

    echo
    if [[ -n "$neoforge_ver" ]]; then
      echo "# NeoForge"
      echo "neoforge = \"$neoforge_ver\""
      echo
    fi

    echo "# Forge"
    echo "forge = \"$forge_ver\""

    echo
    echo "# Libraries"
    echo "mixin = \"$MIXIN_VERSION\""
    echo "mixinextras = \"$MIXINEXTRAS_VERSION\""
    echo "gson = \"$GSON_VERSION\""

    if [[ -n "$amber_version" ]]; then
      echo
      echo "# Amber"
      echo "amber = \"$amber_version\""
    fi

    echo
    echo "# Gradle Plugins"
    echo "fabric-loom-plugin = \"$loom_ver\""
    if [[ -n "$moddev_ver" ]]; then
      echo "neoforge-moddev-plugin = \"$moddev_ver\""
    fi
    echo "modpublisher-plugin = \"$MODPUBLISHER_PLUGIN\""
    echo "forgegradle-plugin = \"[${forgegradle_ver},6.2)\""
    echo "mixin-plugin = \"$MIXIN_PLUGIN\""

    echo
    echo "[libraries]"
    echo "# Core Dependencies"
    echo "minecraft = { group = \"com.mojang\", name = \"minecraft\", version.ref = \"minecraft\" }"
    echo "gson = { group = \"com.google.code.gson\", name = \"gson\", version.ref = \"gson\" }"

    echo
    echo "# Mixin"
    echo "mixin = { group = \"org.spongepowered\", name = \"mixin\", version.ref = \"mixin\" }"
    echo "mixin-extras = { group = \"io.github.llamalad7\", name = \"mixinextras-common\", version.ref = \"mixinextras\" }"

    echo
    echo "# Fabric"
    echo "fabric-loader = { group = \"net.fabricmc\", name = \"fabric-loader\", version.ref = \"fabric-loader\" }"
    echo "fabric-api = { group = \"net.fabricmc.fabric-api\", name = \"fabric-api\", version.ref = \"fabric-api\" }"
    if [[ -n "$modmenu_ver" && "$modmenu_ver" != "null" ]]; then
      echo "modmenu = { group = \"com.terraformersmc\", name = \"modmenu\", version.ref = \"modmenu\" }"
    fi

    if [[ -n "$neoforge_ver" ]]; then
      echo
      echo "# NeoForge"
      echo "neoforge = { group = \"net.neoforged\", name = \"neoforge\", version.ref = \"neoforge\" }"
    fi

    echo
    echo "# Forge"
    echo "forge = { group = \"net.minecraftforge\", name = \"forge\", version.ref = \"forge\" }"

    echo
    echo "# Parchment"
    echo "parchment = { group = \"org.parchmentmc.data\", name = \"parchment-${parchment_mc}\", version.ref = \"parchment\" }"

    if [[ -n "$amber_version" ]]; then
      echo
      echo "# Amber (all platforms)"
      echo "amber = { group = \"com.iamkaf.amber\", name = \"amber-common\", version.ref = \"amber\" }"
      echo "amber-fabric = { group = \"com.iamkaf.amber\", name = \"amber-fabric\", version.ref = \"amber\" }"
      if [[ -n "$neoforge_ver" ]]; then
        echo "amber-neoforge = { group = \"com.iamkaf.amber\", name = \"amber-neoforge\", version.ref = \"amber\" }"
      fi
      echo "amber-forge = { group = \"com.iamkaf.amber\", name = \"amber-forge\", version.ref = \"amber\" }"
    fi

    echo
    echo "[plugins]"
    echo "fabric-loom = { id = \"fabric-loom\", version.ref = \"fabric-loom-plugin\" }"
    if [[ -n "$moddev_ver" ]]; then
      echo "neoforge-moddev = { id = \"net.neoforged.moddev\", version.ref = \"neoforge-moddev-plugin\" }"
    fi
    echo "modpublisher = { id = \"com.hypherionmc.modutils.modpublisher\", version.ref = \"modpublisher-plugin\" }"
    echo "forgegradle = { id = \"net.minecraftforge.gradle\", version.ref = \"forgegradle-plugin\" }"
    echo "mixin = { id = \"org.spongepowered.mixin\", version.ref = \"mixin-plugin\" }"
  } > "$dir/gradle/libs.versions.toml"

done

# Update settings.gradle includes (add missing includes)
# Keep 1.21.11 and 1.21.10 as-is; append the rest if absent.
for mc in "${VERSIONS[@]}"; do
  inc="include 'mc-$mc'"
  if ! grep -Fxq "$inc" settings.gradle; then
    echo "$inc" >> settings.gradle
  fi
done

echo "Generated catalogs for: ${VERSIONS[*]}"
