import sys, os, importlib
import bpy, addon_utils
ext = bpy.utils.user_resource('EXTENSIONS', path='user_default', create=True)
dst = os.path.join(ext, 'mpfb')
if not os.path.exists(dst):
    os.symlink(os.environ['MPFB_SRC'], dst)
addon_utils.enable('bl_ext.user_default.mpfb', default_set=True)
def M(mod, name): return getattr(importlib.import_module('bl_ext.user_default.mpfb.' + mod), name)
