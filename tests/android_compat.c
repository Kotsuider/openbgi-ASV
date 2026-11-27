/* SPDX-License-Identifier: GPL-2.0-only */
#include "bgi/core.h"
#include "bgi/vm.h"
#include "bgi/sys.h"
#include "bgi/error.h"
#include "bgi/asm.h"
#include "bgi/display.h"
#include "check.h"
static OpenBGI_t* o;
static uint32_t Tick(void* u)
{
	(void)u;
	return 0;
}
static int Perf(void* u, int64_t* n)
{
	(void)u;
	*n = 0;
	return 1;
}
static void Date(void* u, OpenBGI_DateTime_t* t)
{
	(void)u;
	memset(t, 0, sizeof *t);
}
typedef struct Asm
{
	uint8_t code[0x1000]; // the bytes
	uint32_t len;         // how many are used
} Asm_t;

// append a byte
static void A8(Asm_t* a, uint32_t v)
{
	a->code[a->len++] = (uint8_t)v;
}

// append a little-endian 16-bit value
static void A16(Asm_t* a, uint32_t v)
{
	A8(a, v & 0xff);
	A8(a, v >> 8);
}

// append a little-endian 32-bit value
static void A32(Asm_t* a, uint32_t v)
{
	A16(a, v & 0xffff);
	A16(a, v >> 16);
}

static void PushI8(Asm_t* a, int v) // 00
{
	A8(a, 0x00);
	A8(a, (uint32_t)v);
}

static void PushI16(Asm_t* a, int v) // 01
{
	A8(a, 0x01);
	A16(a, (uint32_t)v);
}

static void PushI32(Asm_t* a, uint32_t v) // 02
{
	A8(a, 0x02);
	A32(a, v);
}

// append a bare opcode
static void Op(Asm_t* a, uint32_t op)
{
	A8(a, op);
}

// 17 with the frame pointer reset: a return at fp 0 ends the program
static void Ret(Asm_t* a)
{
	PushI32(a, 0);
	A8(a, 0x11);
	A8(a, 0x17);
}

// ---- running ------------------------------------------------------------------

static Thread_t* gT; // the one thread every program runs on (made by main)

/* Load the program into the thread's code area and step it from offset 0
 * with the frame pointer and stack reset, dispatching through
 * o->vm.optablemain as the scheduler does, until a handler returns a
 * non-zero scheduler code (4: the thread ended) or 100000 steps have
 * passed.  Returns that code, or -1 when an opcode has no handler. */
static int Run(const Asm_t* a)
{
	int rc = 0, steps = 0;
	Thread_t* t = gT;
	memcpy(t->code, a->code, a->len);
	t->codeUsed = a->len;
	Thread_SetIP(t, 0);
	Thread_SetFP(t, 0);
	t->sp = 0;
	while(rc == 0 && steps++ < 100000)
	{
		uint8_t op = Thread_FetchOp(t);
		if(!o->vm.optablemain[op])
			return -1; // the scheduler raises MSG_UNDEFINED_OP here
		rc = o->vm.optablemain[op](o, t);
	}
	return rc;
}

/* the value on top of the stack, without popping (the stack wraps: on an
 * empty stack this reads the last entry) */
static uint32_t Top(void)
{
	Thread_t* t = gT;
	uint32_t i = t->sp ? t->sp - 1 : t->stackEntries - 1;
	return t->stack[i];
}

// the number of values on the evaluation stack
static uint32_t Depth(void)
{
	return gT->sp;
}

/* append the final return, run the program (which has to end normally with exactly one
 * value on the stack) and return that value */
static uint32_t Eval(Asm_t* a)
{
	Ret(a);
	CHECK(Run(a) == 4);
	CHECK(Depth() == 1);
	return Top();
}

// ---- the tests -----------------------------------------------------------------

/* The push instructions: "00" and "01" sign-extend their immediate, "02"
 * pushes it as is; "04" pushes fp - n with the data tag; "05" pushes the
 * code address opcode offset + n with the code tag and "06" the untagged
 * offset, both relative to the opcode's own position. */

static uint32_t DataPtr(uint32_t off)
{
	return off | (o->engine.profile->tagData << VM_TAG_SHIFT(o));
}
// signed LEB128, used by the compact instruction tests
static void Sleb(Asm_t* a, int32_t value)
{
	for(;;)
	{
		uint8_t b = value & 0x7f;
		int32_t next = value >> 7;
		if((next == 0 && !(b & 0x40)) || (next == -1 && (b & 0x40)))
		{
			A8(a, b);
			return;
		}
		A8(a, b | 0x80);
		value = next;
	}
}

static void Typed(Asm_t* a, int32_t value, uint32_t size)
{
	Sleb(a, (value * 4) | (int32_t)size);
}

/* The compact base instructions of 1.667 and 1.685.  In particular, 2E is
 * a + b * imm (not a * b + imm), EA adds its 16-bit offset before storing,
 * and 15 reads an inline target when condition bit 3 is set. */
static void TestCompact(void)
{
	Asm_t a;
	printf("compact\n");
	CHECK(Engine_SelectProfile(o, "1.685"));
	CHECK(o->engine.profile->tagShift == 28 && o->engine.profile->dynFirst == 4);
	Vm_TablesInit(o);

	a.len = 0;
	PushI32(&a, 0x50000000);
	PushI32(&a, 2);
	Op(&a, 0x2e);
	Sleb(&a, 4);
	CHECK(Eval(&a) == 0x50000008u);

	a.len = 0;
	Op(&a, 0x03);
	A8(&a, 2);
	Sleb(&a, 13);
	Sleb(&a, 1280);
	Sleb(&a, 960);
	Ret(&a);
	CHECK(Run(&a) == 4);
	CHECK(Depth() == 3);
	CHECK(gT->stack[0] == 13 && gT->stack[1] == 1280 && Top() == 960);

	a.len = 0;
	Op(&a, 0x13);
	A16(&a, 3);
	PushI32(&a, 0x1234);
	CHECK(Eval(&a) == 0x1234);

	a.len = 0;
	PushI32(&a, 1);
	Op(&a, 0x15);
	A8(&a, 8);
	A16(&a, 9);
	PushI32(&a, 0xdead);
	PushI32(&a, 0x2468);
	CHECK(Eval(&a) == 0x2468);

	a.len = 0;
	PushI32(&a, 0x100);
	Op(&a, 0x11);
	PushI32(&a, 11);
	PushI32(&a, 22);
	Op(&a, 0xe2);
	A8(&a, 1);
	A16(&a, 0x8040);
	A16(&a, 0x8044);
	Op(&a, 0xe3);
	A8(&a, 1);
	A16(&a, 0x8040);
	A16(&a, 0x8044);
	Ret(&a);
	CHECK(Run(&a) == 4);
	CHECK(Depth() == 2);
	CHECK(gT->stack[0] == 22 && Top() == 11);

	a.len = 0;
	PushI32(&a, DataPtr(0x40));
	Op(&a, 0x0d);
	Typed(&a, 123, 2);
	Ret(&a);
	CHECK(Run(&a) == 4 && *(uint32_t*)(gT->data + 0x40) == 123);
	a.len = 0;
	PushI32(&a, 0x100);
	Op(&a, 0x11);
	Op(&a, 0x0e);
	A16(&a, 0x40);
	Typed(&a, 456, 2);
	Ret(&a);
	CHECK(Run(&a) == 4 && *(uint32_t*)(gT->data + 0xc0) == 456);

	/* 0E's local is a full 16-bit offset (the size is in the immediate):
	 * cnfgwnd._bp stores its result at fp - 0x46F0 in an 18336-byte frame */
	{
		Thread_t* small = gT;
		gT = Thread_New(o, 0x100, 0x1000, 0x8000);
		CHECK(gT != NULL && gT->valid);
		a.len = 0;
		PushI32(&a, 0x4800);
		Op(&a, 0x11);
		Op(&a, 0x0e);
		A16(&a, 0x46f0);
		Typed(&a, -1, 2);
		Ret(&a);
		CHECK(Run(&a) == 4 && *(int32_t*)(gT->data + 0x110) == -1 && *(int32_t*)(gT->data + 0x4110) == 0);
		Thread_Delete(o, gT);
		gT = small;
	}

	memcpy(gT->data + 0x80, "MakerLogo", 10);
	*(uint32_t*)(gT->data + 0x40) = DataPtr(0x80);
	a.len = 0;
	PushI32(&a, 0x100);
	Op(&a, 0x11);
	Op(&a, 0xea);
	A16(&a, 0x80c0);
	A16(&a, 4);
	Sleb(&a, 0);
	Ret(&a);
	CHECK(Run(&a) == 4 && gT->data[0x84] == 0 && memcmp(gT->data + 0x80, "Make", 4) == 0);

	/* the size bits are the access size only: the pointer is still a dword
	 * (cnfgwnd._bp's Default writes word and byte settings this way) */
	memset(gT->data + 0x80, 0x55, 0x30);
	a.len = 0;
	PushI32(&a, 0x100);
	Op(&a, 0x11);
	Op(&a, 0xea); // word 4 at +10
	A16(&a, 0x40c0);
	A16(&a, 10);
	Sleb(&a, 4);
	Op(&a, 0xea); // byte 1 at +36
	A16(&a, 0x00c0);
	A16(&a, 36);
	Sleb(&a, 1);
	Ret(&a);
	CHECK(Run(&a) == 4 && *(int16_t*)(gT->data + 0x8a) == 4 && gT->data[0x8c] == 0x55 && gT->data[0xa4] == 1 && gT->data[0xa5] == 0x55);

	/* FA: the index is a dword whatever the size bits say (they size the
	 * value read): bitmap._bp reads byte 0 of name slot 0x80 this way */
	*(uint32_t*)(gT->data + 0x40) = 0x80; // the index, at fp - 0xc0
	memset(gT->data + 0x200, 0, 0x100);
	gT->data[0x280] = 0x5a;
	a.len = 0;
	PushI32(&a, 0x100);
	Op(&a, 0x11);
	Op(&a, 0xfa);
	A32(&a, DataPtr(0x200)); // add
	A16(&a, 0x00c0);         // the index local, byte access
	A16(&a, 1);              // mul
	CHECK(Eval(&a) == 0x5a);

	*(uint32_t*)(gT->data + 0x40) = 18;
	a.len = 0;
	PushI32(&a, 0x100);
	Op(&a, 0x11);
	Op(&a, 0xef);
	A32(&a, DataPtr(0x40) | 0x80000000u);
	Ret(&a);
	PushI32(&a, 0x1357);
	Op(&a, 0x17);
	CHECK(Eval(&a) == 0x1357);

	CHECK(Engine_SelectProfile(o, "1.69/444"));
	Vm_TablesInit(o);
}


static int FallbackA(OpenBGI_t* engine, Thread_t* thread)
{
	(void)engine;
	(void)thread;
	return 11;
}
static int FallbackB(OpenBGI_t* engine, Thread_t* thread)
{
	(void)engine;
	(void)thread;
	return 22;
}

static void TestEnabledHandlers(void)
{
	VmHandler_t table[256];
	const OpEntry_t later[] = {{0x18, FallbackA, GEN_1_492, GEN_LAST}};
	CHECK(Engine_SelectProfile(o, "1.69/472"));
	CHECK(Engine_OpcodeOverride(o, OPFAM_C0, 0x18) == -1);
	Vm_FillTable(o, table, OPFAM_C0, later, 1);
	CHECK(table[0x18] != FallbackA);
	CHECK(Engine_OverrideOpcode(o, "C0:18", 1));
	Vm_FillTable(o, table, OPFAM_C0, later, 1);
	CHECK(table[0x18] == FallbackA);
	Vm_TablesInit(o);
	CHECK(o->vm.optableC0[0x18] && o->vm.optableC0[0x18] != Opcode_NotImplemented);
	const OpEntry_t exact[] = {{0x18, FallbackA, GEN_FIRST, GEN_LAST}, {0x18, FallbackB, GEN_1_69_472, GEN_1_69_472}};
	Vm_FillTable(o, table, OPFAM_C0, exact, 2);
	CHECK(table[0x18] == FallbackB);
	const OpEntry_t neighbours[] = {{0x18, FallbackA, GEN_FIRST, GEN_1_69_451}, {0x18, FallbackB, GEN_1_492, GEN_LAST}};
	Vm_FillTable(o, table, OPFAM_C0, neighbours, 2);
	CHECK(table[0x18] == Opcode_NotImplemented);
	const OpEntry_t same[] = {{0x18, FallbackA, GEN_FIRST, GEN_1_69_451}, {0x18, FallbackA, GEN_1_492, GEN_LAST}};
	Vm_FillTable(o, table, OPFAM_C0, same, 2);
	CHECK(table[0x18] == FallbackA);
	const OpEntry_t closest[] = {{0x18, FallbackA, GEN_1_529, GEN_LAST}, {0x18, FallbackB, GEN_1_492, GEN_1_492}};
	Vm_FillTable(o, table, OPFAM_C0, closest, 2);
	CHECK(table[0x18] == FallbackB);
	const OpEntry_t earlier[] = {{0x18, FallbackB, GEN_FIRST, GEN_1_69_451}};
	Vm_FillTable(o, table, OPFAM_C0, earlier, 1);
	CHECK(table[0x18] == FallbackB);
	Vm_FillTable(o, table, OPFAM_C0, NULL, 0);
	CHECK(table[0x18] == Opcode_NotImplemented);
	CHECK(Engine_OverrideOpcode(o, "C0:18", 0));
	Vm_FillTable(o, table, OPFAM_C0, later, 1);
	CHECK(table[0x18] == NULL);
}

int main(void)
{
	OpenBGI_Host_t host = {.size = sizeof host, .api = OPENBGI_API_VERSION, .ticksMs = Tick, .perfNs = Perf, .localTime = Date};
	o = OpenBGI_New(&host);
	CHECK(o);
	if(!o)
		return 1;
	gT = Thread_New(o, 256, 0x1000, 0x1000);
	TestCompact();
	TestEnabledHandlers();
	CHECK(Engine_SelectProfile(o, "1.685"));
	DynMem_Init(o);
	uint32_t p = GAlloc(o, 4097);
	CHECK((p >> 28) == 5);
	CHECK(GFree(o, p));
	Display_InitSizeTable(o);
	CHECK(Display_SetSizeEntry(o, 15, 1920, 1080) == 0);
	CHECK(Engine_SelectProfile(o, "1.669"));
	CHECK(Display_SetSizeEntry(o, 8, 1920, 1080) == 1);
	AsmInsn_t instruction;
	const uint8_t pushes[] = {3, 2, 13, 0x80, 0x0a, 0xc0, 7};
	CHECK(Asm_Decode(pushes, sizeof pushes, 0, GEN_1_685, &instruction));
	CHECK(instruction.count == 4 && instruction.v[1] == 13 && instruction.v[2] == 1280 && instruction.v[3] == 960);
	CHECK(!Asm_Decode(pushes, 1, 0, GEN_1_685, &instruction));
	CHECK(!Asm_Decode(pushes, 3, 0, GEN_1_685, &instruction));
	const uint8_t call[] = {0xef, 0x40, 0, 0, 0xa0};
	CHECK(Asm_Decode(call, sizeof call, 0, GEN_1_685, &instruction) && instruction.fam == 0 && instruction.len == 5);
	const uint8_t locals[] = {0xe2, 1, 0x40, 0x80, 0x44, 0x80};
	CHECK(Asm_Decode(locals, sizeof locals, 0, GEN_1_685, &instruction) && instruction.count == 3);
	CHECK(!Asm_Decode(locals, 1, 0, GEN_1_685, &instruction));
	CHECK(!Asm_Decode(locals, 4, 0, GEN_1_685, &instruction));
	AsmResult_t assembled;
	const char listing[] = "push_values 2, 13, 1280, 960\n";
	Asm_Assemble(o, listing, strlen(listing), GEN_1_685, stderr, &assembled);
	CHECK(!assembled.errors && assembled.file && assembled.size == sizeof pushes + 16);
	if(assembled.file)
		CHECK(!memcmp(assembled.file + 16, pushes, sizeof pushes));
	BGI_Free(assembled.file);
	BGI_Free(assembled.labels);
	const char jumpListing[] = "jmp_rel target\ntarget:\n";
	Asm_Assemble(o, jumpListing, strlen(jumpListing), GEN_1_685, stderr, &assembled);
	CHECK(!assembled.errors && assembled.file && assembled.size == 19);
	if(assembled.file)
		CHECK(assembled.file[16] == 0x13 && assembled.file[17] == 3 && assembled.file[18] == 0);
	BGI_Free(assembled.file);
	BGI_Free(assembled.labels);
	Thread_Delete(o, gT);
	OpenBGI_Delete(o);
	printf("android_compat: %d failures\n", gFails);
	return gFails ? 1 : 0;
}
