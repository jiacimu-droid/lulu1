"""Original game meshes and treasure sprites. Run with Blender's background Python."""
import bpy
import json
import math
import os
import random
import sys
from mathutils import Vector

out = os.path.abspath(sys.argv[sys.argv.index("--") + 1])
os.makedirs(out, exist_ok=True)
random.seed(71)
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)

def material(name, hexcode, metal=0.0, rough=.65, grain=False):
    rgb = tuple(int(hexcode[i:i+2], 16) / 255 for i in (0, 2, 4))
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*rgb, 1)
    m.use_nodes = True
    n = m.node_tree.nodes
    p = n.get("Principled BSDF")
    p.inputs["Base Color"].default_value = (*[v**2.2 for v in rgb], 1)
    p.inputs["Metallic"].default_value = metal
    p.inputs["Roughness"].default_value = rough
    if grain:
        tex = n.new("ShaderNodeTexNoise")
        tex.inputs["Scale"].default_value = 10
        tex.inputs["Detail"].default_value = 4
        bump = n.new("ShaderNodeBump")
        bump.inputs["Strength"].default_value = .26
        bump.inputs["Distance"].default_value = .085
        m.node_tree.links.new(tex.outputs["Fac"], bump.inputs["Height"])
        m.node_tree.links.new(bump.outputs["Normal"], p.inputs["Normal"])
    return m

def cube(name, xyz, size, mat, bevel=.021, parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=xyz)
    o = bpy.context.object
    o.name = name
    o.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    o.data.materials.append(mat)
    if bevel > 0:
        b = o.modifiers.new("Hand-worn edges", "BEVEL")
        b.width = bevel
        b.segments = 2
    if parent:
        o.parent = parent
    return o

def rock(name, xyz, radii, mat, seed=1):
    rng = random.Random(seed)
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=2, radius=1, location=xyz)
    o = bpy.context.object
    o.name = name
    for v in o.data.vertices:
        f = rng.uniform(.88, 1.09)
        v.co.x *= radii[0] * f
        v.co.y *= radii[1] * f
        v.co.z *= radii[2] * f
    o.data.materials.append(mat)
    return o

def panel(name, points, faces, mat):
    data = bpy.data.meshes.new(name)
    data.from_pydata(points, [], faces)
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(mat)
    return obj

def export_mesh(objects):
    bpy.context.view_layer.update()
    graph = bpy.context.evaluated_depsgraph_get()
    result = []
    for obj in objects:
        if obj.type != "MESH":
            continue
        geo = obj.evaluated_get(graph)
        mesh = geo.to_mesh()
        mesh.calc_loop_triangles()
        for face in mesh.loop_triangles:
            tint = mesh.materials[face.material_index].diffuse_color
            normal = geo.matrix_world.to_3x3() @ face.normal
            shade = .72 + .30 * max(0, normal.normalized().dot(Vector((-.5, -.5, .88)).normalized()))
            colour = [min(255, round(v*shade*255)) for v in tint[:3]]
            for i in face.vertices:
                x, depth, height = geo.matrix_world @ mesh.vertices[i].co
                result.extend((round(x, 3), round(height, 3), round(depth, 3), *colour))
        geo.to_mesh_clear()
    return result

bark = [material("Cedar bark " + str(i), h) for i,h in enumerate(("77604B","675345","9A7C56","534838"))]
needle = [material("Cedar foliage " + str(i), h) for i,h in enumerate(("516744","365641","668155","84965E","274536","A3A873"))]
slate = [material("Coastal stone " + str(i), h) for i,h in enumerate(("777F79","9A9C8B","57666B","758260","A8AC9A"))]
wood = material("Fog-weathered timber", "927A5B")
dark_wood = material("Cedar siding", "6D503A")
iron = material("Dark iron", "4C5454")
cream = material("Lighthouse limewash", "E0CA9E")
oxide = material("Lighthouse copper", "A16650")
lantern = material("Lamp glass", "FFD58C")
baked = {}
for kind in ("cedar", "shore-rock", "ore", "beacon", "cave", "dock"):
    old = {o.name for o in bpy.data.objects}
    if kind == "cedar":
        for k in range(5):
            width = .25-k*.018
            cube("Cedar heartwood", (k*.013,0,.26+k*.33), (width,width,.58),bark[k%4],.022)
            for dx,dy in ((-.09,-.1),(.11,.02),(-.05,.11)):
                cube("Bark fissure", (dx,dy,.27+k*.33),(.022,.028,.31), bark[3],.003)
        for k in range(6):
            span=.94-k*.125
            h=.95+k*.20
            for j in range(5):
                angle=j*math.tau/5+k*.67
                a=span*.31
                needle_box=cube("Dense cedar spray", (math.cos(angle)*a,math.sin(angle)*a,h+random.uniform(-.08,.07)),
                    (span*.58,span*.58,.26+random.random()*.16),needle[(k+j)%6],.025)
                needle_box.rotation_euler.z=j*.31
        for j in range(4):
            angle=j*math.pi/2
            root=cube("Exposed root",(math.cos(angle)*.15,math.sin(angle)*.15,.07),(.35,.08,.11),bark[j],.017)
            root.rotation_euler.z=angle
    elif kind in ("shore-rock", "ore"):
        rock("Layered bedrock",(-.08,.03,.16),(.41,.38,.30),slate[2],1)
        rock("Sea-worn upper layer",(-.09,.02,.34),(.40,.35,.22),slate[0],2)
        rock("Small detached rock",(.28,-.12,.13),(.20,.25,.18),slate[1],3)
        rock("Moss on the crown",(-.17,.08,.49),(.28,.24,.08),slate[3],6)
        if kind=="ore":
            for j in range(5):
                gem=material("Mineral vein "+str(j),("7BBDBD","C1E1D1","5E949C","97CECB","477C87")[j],.4,.24)
                o=rock("Quartz wedge",(.10+j*.017,-.19+j*.065,.29+j*.045),(.065,.06,.15),gem,j+13)
                o.rotation_euler.y=j*.25
    elif kind == "beacon":
        cube("Stone plinth",(0,0,.17),(1.24,1.23,.34),slate[2],.055)
        for j in range(14):
            w=.88-j*.018
            cube("Limewashed wall band",(0,0,.36+j*.205),(w,w,.205),cream if j not in (3,4,9,10) else oxide,.025)
        for y in (-.33,.33):
            cube("Black window recess",(0,y,1.9),(.26,.05,.42),iron,.025)
            cube("Brass mullion",(0,y*1.05,1.9),(.027,.038,.36),wood,.004)
        cube("Balcony",(0,0,3.26),(1.22,1.2,.15),oxide,.025)
        for x in (-.49,.49):
            for y in (-.49,.49):
                cube("Iron railing",(x,y,3.49),(.058,.058,.4),iron,.008)
        cube("Lantern housing",(0,0,3.52),(.64,.64,.50),lantern,.018)
        for i in (-1,1):
            for j in (-1,1):
                cube("Lantern frame",(i*.33,j*.33,3.54),(.06,.06,.66),dark_wood,.014)
        bpy.ops.mesh.primitive_cone_add(vertices=8,radius1=.56,depth=.57,location=(0,0,4.06))
        roof=bpy.context.object;roof.name="Beacon roof";roof.data.materials.append(oxide)
    elif kind == "cave":
        for j in range(5):
            for sign in (-1,1):
                rock("Cave slate buttress",(sign*(.83-j*.025),0,.26+j*.33),(.45,.61,.34),slate[j%4],13+j+sign)
        for i in range(5):
            rock("Lintel strata",((i-2)*.36,0,1.91+max(0,.09*(2-abs(i-2)))),(.38,.62,.35),slate[i%3],31+i)
    else:
        for j in range(5):
            cube("Dock boards",(0,j*.27-.55,.18),(1.08,.245,.13), bark[j%3],.02)
        for x in (-.45,.45):
            for y in (-.48,.48):
                cube("Mooring post",(x,y,.30),(.13,.13,.62),dark_wood,.024)
                cube("Post cap",(x,y,.62),(.19,.19,.05),iron,.015)
    added=[o for o in bpy.data.objects if o.name not in old]
    if kind in ("cedar","beacon","dock"):
        for obj in added:
            if obj.type=="MESH" and obj.modifiers:
                decimate=obj.modifiers.new("Mobile mesh budget","DECIMATE")
                decimate.ratio=.45
    baked[kind]=export_mesh(added)
    for o in added:
        bpy.data.objects.remove(o,do_unlink=True)
with open(os.path.join(out,"island-meshes.json"),"w") as f:
    json.dump(baked,f,separators=(",",":"))
if os.getenv("LULU_MODELS_ONLY"):
    sys.exit(0)

oak = [material("Quarter sawn walnut " + str(i),h,0,.67,True) for i,h in enumerate(("563329","623D32","744733","4C332C"))]
gold = material("Carved oxidized bronze", "AE8750", .72,.39,True)
dark = material("Blackened bronze", "554B42", .75,.46,True)
velvet = material("Indigo lining", "252E3D", .12,.95,True)
jewel = material("Moonstone inlay", "5BA9A4", .48,.23)
for j in range(7):
    cube("Front walnut slat",((j-3)*.247,-.499,.343),(.243,.107,.525),oak[j%4])
    cube("Rear walnut slat",((j-3)*.247,.499,.343),(.243,.102,.525),oak[(j+1)%4])
for i in (-1,1):
    for j in range(4):
        cube("Side walnut slat",(i*.856,(j-1.5)*.248,.34),(.103,.24,.54),oak[j%4])
    for j in (-1,1):
        cube("Cast foot",(i*.77,j*.44,.095),(.27,.23,.16),dark,.045)
    cube("Bronze side lip",(i*.85,0,.626),(.10,1.08,.083),gold,.013)
for j in (-1,1):
    for k in (.16,.59):
        cube("Front bronze binding",(0,j*.563,k),(1.83,.07,.077),gold,.014)
cube("Indigo lining",(0,0,.171),(1.55,.88,.13),velvet)
for i in (-1,1):
    for j in (-1,1):
        cube("Embossed corner",(i*.75,j*.557,.385),(.19,.065,.46),gold,.027)
        for k in (0.28,.48):
            rock("Hammered rivet",(i*.754,j*.601,k),(.033,.02,.033),dark,7)
    cube("Ornament rail",(i*.52,-.573,.37),(.23,.034,.032),gold,.011)
    for k in range(3):
        o=cube("Fleur filigree",(i*(.25+k*.12),-.575,.393+k%2*.04),(.073,.036,.073),gold,.012)
        o.rotation_euler.y=math.radians(45)
lock=cube("Keyhole escutcheon",(0,-.59,.48),(.32,.085,.32),gold,.037)
lock.rotation_euler.y=math.radians(45)
rock("Keyhole moonstone",(0,-.645,.48),(.09,.032,.09),jewel,12)
cube("Keyway",(0,-.68,.465),(.033,.013,.105),dark,.009)
pivot=bpy.data.objects.new("Physical lid hinge",None)
bpy.context.collection.objects.link(pivot)
pivot.location=(0,.528,.626)
for j in range(7):
    cube("Lid plank",((j-3)*.248,-.53,.11),(.245,1.104,.19),oak[j%4],.038,pivot)
for i in (-1,1):
    cube("Cast lid rim",(i*.79,-.53,.22),(.15,1.14,.066),gold,.016,pivot)
    for j in range(6):
        cube("Lid filigree",(i*.54,-.145-j*.147,.228),(.18,.034,.025),gold,.004,pivot)
    for y in (-1.05,-.02):
        cube("Binding rim",(0,y,.137),(1.82,.076,.16),gold,.018,pivot)
cube("Lid inner velvet",(0,-.532,.002),(1.51,.88,.061),velvet,.035,pivot)
for j in range(5):
    rock("Lid bezel moonstones",((j-2)*.18,-.57,.233),(.057,.064,.021),jewel,j+40).parent=pivot

scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=28
scene.cycles.use_denoising=False
scene.render.resolution_x=480
scene.render.resolution_y=440
scene.render.resolution_percentage=100
scene.render.film_transparent=True
scene.render.image_settings.color_mode="RGBA"
scene.view_settings.view_transform="AgX"
scene.world.color=(.12,.12,.12)

def aim(obj, target):
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat("-Z","Y").to_euler()
def area(name, pos, strength, color, size, target):
    light=bpy.data.lights.new(name,"AREA")
    light.energy=strength
    light.color=color
    light.shape="DISK"
    light.size=size
    obj=bpy.data.objects.new(name,light)
    bpy.context.collection.objects.link(obj)
    obj.location=pos
    aim(obj,target)
    return obj
area("Honey key", (1.9,-3.8,4.4),580,(1,.77,.50),4,(0,0,.5))
area("Moon fill", (-2.8,-.7,3.2),435,(.53,.76,1),3,(0,0,.5))
area("Rim", (1,2.6,3.6),760,(1,.57,.34),2.5,(0,0,.7))
camdata=bpy.data.cameras.new("Treasure camera");camdata.type="ORTHO";camdata.ortho_scale=2.83
cam=bpy.data.objects.new("Treasure camera",camdata);bpy.context.collection.objects.link(cam)
cam.location=(3.2,-6.6,4.45);aim(cam,(0,0,.79));scene.camera=cam
if os.getenv("LULU_SAVE_BLEND"):
    bpy.ops.wm.save_as_mainfile(filepath=os.path.abspath(os.environ["LULU_SAVE_BLEND"]))
for i,angle in enumerate((0,2,5,10,19,31,45,62,83)):
    pivot.rotation_euler.x=-math.radians(angle)
    scene.render.filepath=os.path.join(out,f"chest-{i}.png")
    bpy.ops.render.render(write_still=True)

for o in list(bpy.data.objects):
    if o.type=="MESH" or o.type=="EMPTY":bpy.data.objects.remove(o,do_unlink=True)
scene.render.resolution_x=scene.render.resolution_y=260
camdata.ortho_scale=1.84
cam.location=(2.4,-4.5,2.75);aim(cam,(0,0,.45))
silver=material("Engraved silver", "A6B5B9",.83,.26,True)
for kind in ("token","jewel","crown"):
    old={o.name for o in bpy.data.objects}
    if kind=="token":
        for rr,depth,ma in ((.51,.105,dark),(.475,.16,gold),(.414,.17,silver)):
            bpy.ops.mesh.primitive_cylinder_add(vertices=48,radius=rr,depth=depth,location=(0,0,.44),rotation=(math.radians(64),0,.15))
            bpy.context.object.data.materials.append(ma)
        for a in range(10):
            t=a*math.tau/10
            rock("Stamped silver dot",(.36*math.cos(t),-.16,.44+.32*math.sin(t)),(.018,.019,.025),gold,a)
        rock("Central cabochon",(0,-.12,.48),(.21,.10,.20),jewel)
    elif kind=="jewel":
        m=material("Violet spinel","A487DB",.53,.15)
        ring=[(math.cos(a*math.tau/8)*.44,math.sin(a*math.tau/8)*.33,.47) for a in range(8)]
        top=[(x*.64,y*.64,.77) for x,y,z in ring]
        faces=[(i,(i+1)%8,(i+1)%8+8,i+8) for i in range(8)]+[(i,16,(i+1)%8) for i in range(8)]+[tuple(range(8,16))]
        panel("Cut spinel",ring+top+[(0,0,.13)],faces,m)
        for i in range(6):
            a=i*math.tau/6
            rock("Ornamental setting",(.42*math.cos(a),.34*math.sin(a),.39),(.11,.08,.13),gold,i)
    else:
        for j in range(12):
            a=j*math.tau/12
            rock("Crown mounts",(.46*math.cos(a),.33*math.sin(a),.31),(.093,.091,.11),gold,35+j)
            if j%2==0:
                o=rock("Crown petals",(.39*math.cos(a),.30*math.sin(a),.52),(.11,.09,.24),gold,19+j)
                o.rotation_euler.y=.23*math.cos(a)
        rock("Amber heart",(0,0,.60),(.25,.21,.30),material("Amber crystal","F1BA65",.57,.16))
        for j in range(4):
            rock("Tiny jade",(.37*math.cos(j*math.pi/2),.33*math.sin(j*math.pi/2),.39),(.068,.052,.095),jewel,j)
    scene.render.filepath=os.path.join(out,f"loot-{kind}.png")
    bpy.ops.render.render(write_still=True)
    for o in list(bpy.data.objects):
        if o.name not in old:bpy.data.objects.remove(o,do_unlink=True)
