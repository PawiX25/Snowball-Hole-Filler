# Snowball Hole Filler

Snowball Hole Filler is a Minecraft mod for Fabric that automatically fills holes created by snowballs.

## Features

- Detects when a snowball lands and fills nearby holes.
- Automatically fills the holes with appropriate blocks based on surrounding blocks.
- Configurable settings for radius, depth, and enable/disable the mod.
- Undo functionality to revert the last operation.
- Tracks player statistics such as total blocks filled and most used blocks.
- Provides in-game commands to configure and control the mod.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/).
2. Download the mod's `.jar` file from the releases.
3. Place the `.jar` file into the `mods` folder located in your Minecraft directory.

## Usage

The mod works automatically after installation. You can configure its behavior using in-game commands.

### Commands

- `/snowballfiller radius <value>`: Set the radius of the area to fill (default is 3, max 10).
- `/snowballfiller depth <value>`: Set the maximum depth to fill (default is 3, max 10).
- `/snowballfiller toggle`: Enable or disable the snowball hole filler.
- `/snowballfiller status`: Display the current configuration and status.
- `/snowballfiller undo`: Undo the last fill operation.
- `/snowballfiller stats`: Display your statistics, including total blocks filled and most used blocks.

### Configuration

- **Radius**: Controls how far from the snowball impact point the mod will search for holes to fill.
- **Max Depth**: Controls the maximum depth of holes that the mod will fill.
- **Enabled**: Toggles the mod on or off.

## Building from Source

To build the mod from source, you need to have [Gradle](https://gradle.org/) installed.

1. Clone the repository.
2. Navigate to the project directory.
3. Run `./gradlew build` (on Unix systems) or `gradlew.bat build` (on Windows).
4. The built `.jar` file will be located in the `build/libs` directory.
