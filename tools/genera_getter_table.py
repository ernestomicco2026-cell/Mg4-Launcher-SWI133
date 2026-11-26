# Rigenera le righe di app/java/com/mg4/launcher/svc/GetterTable.java dal vehiclesettingservice del firmware:
#   python3 -I tools/genera_getter_table.py <vehiclesettingservice_my24_P.apk>  (serve androguard)
import sys, re, logging
logging.disable(logging.CRITICAL)
from loguru import logger; logger.remove()
from androguard.misc import AnalyzeAPK
a, ds, dx = AnalyzeAPK(sys.argv[1])
SV={'IAirConditionService':'aircondition','IVehicleChargingService':'vehiclecharging','IVehicleConditionService':'vehiclecondition',
    'IVehicleControlService':'vehiclecontrol','IVehicleSettingService':'vehiclesetting'}
pref='Lcom/saicmotor/sdk/vehiclesettings/'
rows=[]; stats={}
for d in ds:
  for c in d.get_classes():
    n=c.get_name()
    for itf,svc in SV.items():
      if n==pref+itf+';':
        meths={m.get_name():m.get_descriptor() for m in c.get_methods()}
        stub=None
        for d2 in ds:
          for c2 in d2.get_classes():
            if c2.get_name()==pref+itf+'$Stub;': stub=c2
        codes={}
        for f in stub.get_fields():
          if f.get_name().startswith('TRANSACTION_'):
            iv=f.get_init_value()
            codes[f.get_name()[12:]]=iv.get_value() if iv else None
        cnt=0
        for name,desc in meths.items():
          m=re.fullmatch(r'\(\)([IFZ])',desc)
          if not m or not (name.startswith('get') or name.startswith('is')): continue
          code=codes.get(name)
          if code is None: print('NOCODE',itf,name,file=sys.stderr); continue
          rows.append((list(SV).index(itf),int(code),svc,itf,name,m.group(1))); cnt+=1
        stats[itf]=(cnt,len(meths),len(codes))
rows.sort()
print(stats,file=sys.stderr)
for r in rows: print('            {"%s", "%s", "%d", "%s", "%s"},'%(r[2],r[3],r[1],r[4],r[5]))
