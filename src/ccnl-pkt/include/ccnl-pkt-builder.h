/*
 * @f ccnl-pkt-bilder.h
 * @b CCN lite - CCN packet builder
 *
 * Copyright (C) 2011-17, University of Basel
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

#ifndef CCNL_PKT_BUILDER
#define CCNL_PKT_BUILDER

#include "ccnl-core.h"

#include "ccnl-pkt-ccnb.h"
#include "ccnl-pkt-ccntlv.h"
#include "ccnl-pkt-cistlv.h"
#include "ccnl-pkt-iottlv.h"
#include "ccnl-pkt-ndntlv.h"
#include "ccnl-pkt-switch.h"
#include "ccnl-pkt-localrpc.h"

#ifdef USE_SUITE_CCNB
int ccnb_isContent(unsigned char *buf, int len);
#endif // USE_SUITE_CCNB

#ifdef USE_SUITE_CCNTLV

struct ccnx_tlvhdr_ccnx2015_s*
ccntlv_isHeader(unsigned char *buf, int len);

int ccntlv_isData(unsigned char *buf, int len);

int ccntlv_isFragment(unsigned char *buf, int len);
#endif // USE_SUITE_CCNTLV

#ifdef USE_SUITE_CISTLV

int cistlv_isData(unsigned char *buf, int len);
#endif // USE_SUITE_CISTLV

#ifdef USE_SUITE_IOTTLV
int iottlv_isReply(unsigned char *buf, int len);

int iottlv_isFragment(unsigned char *buf, int len);
#endif // USE_SUITE_IOTTLV

#ifdef USE_SUITE_NDNTLV
int ndntlv_isData(unsigned char *buf, int len);
#endif // USE_SUITE_NDNTLV

#ifdef NEEDS_PACKET_CRAFTING
typedef int (*ccnl_mkInterestFunc)(struct ccnl_prefix_s*, int*, unsigned char*, int);
#endif // NEEDS_PACKET_CRAFTING
typedef int (*ccnl_isContentFunc)(unsigned char*, int);
typedef int (*ccnl_isFragmentFunc)(unsigned char*, int);

ccnl_isContentFunc
ccnl_suite2isContentFunc(int suite);

ccnl_isFragmentFunc
ccnl_suite2isFragmentFunc(int suite);

#ifdef NEEDS_PACKET_CRAFTING
struct ccnl_buf_s*
ccnl_mkSimpleContent(struct ccnl_prefix_s *name,
                     unsigned char *payload, int paylen, int *payoffset);

struct ccnl_buf_s*
ccnl_mkSimpleInterest(struct ccnl_prefix_s *name, int *nonce);
#endif

#endif //CCNL_PKT_BUILDER
