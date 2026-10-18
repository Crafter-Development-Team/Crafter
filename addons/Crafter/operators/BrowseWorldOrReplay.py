"""Browse for a world folder or a ReplayMod .mcpr file (minimal UI for replay import)."""
import bpy
from bpy.props import StringProperty
from bpy_extras.io_utils import ImportHelper
from ..config import __addon_name__


class CRAFTER_OT_browse_world_or_replay(bpy.types.Operator, ImportHelper):
    bl_idname = "crafter.browse_world_or_replay"
    bl_label = "Browse World / Replay"
    bl_description = "Select a Minecraft world folder or a ReplayMod .mcpr recording"

    filename_ext = ""
    filter_glob: StringProperty(default="*.mcpr", options={"HIDDEN"})  # type: ignore
    # ImportHelper always picks files; for folders users still use the path field / History.
    # .mcpr is the new case this operator exists for.

    filepath: StringProperty(subtype="FILE_PATH")  # type: ignore

    def execute(self, context):
        addon_prefs = context.preferences.addons[__addon_name__].preferences
        path = bpy.path.abspath(self.filepath)
        if not path:
            return {"CANCELLED"}
        addon_prefs.World_Path = path
        self.report({"INFO"}, f"Path set: {path}")
        return {"FINISHED"}
