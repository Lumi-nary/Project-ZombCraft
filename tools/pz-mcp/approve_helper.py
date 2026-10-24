import importlib.util as _u, pathlib as _p
_spec = _u.spec_from_file_location("approve_pzcraft", _p.Path(__file__).with_name("approve-pzcraft.py"))
_mod = _u.module_from_spec(_spec); _spec.loader.exec_module(_mod)
approve = _mod.approve
