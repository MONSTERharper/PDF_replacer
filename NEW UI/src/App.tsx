import React, { useState, useRef, useEffect, useCallback } from 'react';
import {
  Trash2,
  Plus,
  RotateCcw,
  CheckCircle2,
  ShieldCheck,
  Search,
  Zap,
  Replace,
  Combine,
  Scissors,
  Minimize2,
  FileType,
  PenTool,
  Settings,
  ChevronRight,
} from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';
import {
  postReplaceBatch,
  postContactInquiry,
  type ReplacePair,
  type MatchMode,
  type ApiReplaceScope,
} from './replaceApi';

type View = 'dashboard' | 'replace' | 'merge' | 'split' | 'compress' | 'convert' | 'sign' | 'extract' | 'metadata' | 'about' | 'contact';

const BoltBrand = ({ text, className = "" }: { text: string, className?: string }) => {
  const parts = text.split(/(bolt)/gi);
  return (
    <span className={className}>
      {parts.map((part, i) => 
        part.toLowerCase() === 'bolt' ? (
          <span key={i} className="inline-flex items-center gap-0.5">
            <span className="text-[#FF3300] lowercase">bolt</span>
            <Zap size={14} className="fill-[#FF3300] text-[#FF3300] rotate-12 shrink-0" />
          </span>
        ) : part
      )}
    </span>
  );
};

export default function App() {
  const [currentView, setCurrentView] = useState<View>('dashboard');
  const [files, setFiles] = useState<File[]>([]);
  const [pairs, setPairs] = useState<ReplacePair[]>([{ find: '', replace: '' }]);
  const [isProcessing, setIsProcessing] = useState(false);
  const [log, setLog] = useState<{ msg: string; type: 'info' | 'success' | 'error' }[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const fontInputRef = useRef<HTMLInputElement>(null);

  const [matchMode, setMatchMode] = useState<MatchMode>('exact');
  const [replaceScope, setReplaceScope] = useState<ApiReplaceScope>('all');
  const [occurrenceIndex, setOccurrenceIndex] = useState(1);
  const [preserveStyle, setPreserveStyle] = useState(true);
  const [strict, setStrict] = useState(false);
  const [fontFile, setFontFile] = useState<File | null>(null);

  const [contactName, setContactName] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactSubject, setContactSubject] = useState('');
  const [contactMessage, setContactMessage] = useState('');
  const [contactStatus, setContactStatus] = useState<{ msg: string; type: 'ok' | 'error' } | null>(null);
  const [contactSending, setContactSending] = useState(false);

  const addLog = (msg: string, type: 'info' | 'success' | 'error' = 'info') => {
    setLog(prev => [{ msg: `[${new Date().toLocaleTimeString()}] ${msg}`, type }, ...prev].slice(0, 50));
  };

  const goToView = useCallback((view: View, path: string) => {
    setCurrentView(view);
    window.history.pushState({}, '', path);
  }, []);

  useEffect(() => {
    const syncFromPath = () => {
      const path = window.location.pathname.replace(/\/+$/, '') || '/';
      if (path === '/about') setCurrentView('about');
      else if (path === '/contact') setCurrentView('contact');
      else if (path === '/replace') setCurrentView('replace');
      else setCurrentView('dashboard');
    };
    syncFromPath();
    window.addEventListener('popstate', syncFromPath);
    return () => window.removeEventListener('popstate', syncFromPath);
  }, []);

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const list: File[] = e.target.files ? Array.from(e.target.files) : [];
    const pdfs = list.filter((f) => f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf'));
    if (pdfs.length === 0) {
      addLog('Please choose one or more PDF files.', 'error');
      return;
    }
    setFiles(pdfs);
    addLog(`Loaded ${pdfs.length} PDF file(s).`, 'info');
  };

  const handleFontUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    setFontFile(f ?? null);
    if (f) addLog(`Fallback font: ${f.name}`, 'info');
  };

  const handleRunReplacement = async () => {
    if (files.length === 0) return;
    setIsProcessing(true);
    addLog('Sending PDFs to PDFBolt engine...');
    try {
      const rules = pairs.map((p) => ({ find: p.find.trim(), replace: p.replace }));
      if (rules.some((p) => !p.find)) {
        throw new Error('Find text cannot be empty.');
      }
      if (replaceScope === 'nth' && (!Number.isFinite(occurrenceIndex) || occurrenceIndex < 1)) {
        throw new Error('Occurrence index must be a positive number.');
      }
      const result = await postReplaceBatch({
        files,
        pairs: rules,
        matchMode,
        replaceScope,
        occurrenceIndex: replaceScope === 'nth' ? occurrenceIndex : undefined,
        strict,
        preserveStyle,
        font: fontFile,
      });
      const url = URL.createObjectURL(result.blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = result.filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      addLog(
        `Done. ${result.matches} replacement(s) applied from ${result.matchesFound} match(es). Style-preserved: ${result.stylePreserved}, fallback-font: ${result.styleFallback}.`,
        'success',
      );
    } catch (err) {
      addLog(err instanceof Error ? err.message : String(err), 'error');
    } finally {
      setIsProcessing(false);
    }
  };

  const handleContactSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const name = contactName.trim();
    const email = contactEmail.trim();
    const subject = contactSubject.trim();
    const message = contactMessage.trim();
    if (!name || !email || !subject || !message) {
      setContactStatus({ msg: 'Please fill all fields.', type: 'error' });
      return;
    }
    setContactSending(true);
    setContactStatus(null);
    try {
      await postContactInquiry({ name, email, subject, message });
      setContactStatus({ msg: 'Inquiry sent successfully.', type: 'ok' });
      setContactName('');
      setContactEmail('');
      setContactSubject('');
      setContactMessage('');
    } catch (err) {
      setContactStatus({ msg: err instanceof Error ? err.message : String(err), type: 'error' });
    } finally {
      setContactSending(false);
    }
  };

  const tools = [
    { id: 'replace', name: 'bolt replace', icon: Replace, description: 'Replace text inside PDF content streams.', status: 'live', highlight: true },
    { id: 'merge', name: 'bolt merge', icon: Combine, description: 'Combine multiple PDF files.', status: 'wip' },
    { id: 'split', name: 'bolt split', icon: Scissors, description: 'Divide PDF into separate pages.', status: 'wip' },
    { id: 'compress', name: 'bolt compress', icon: Minimize2, description: 'Reduce PDF file size.', status: 'wip' },
    { id: 'convert', name: 'bolt convert', icon: FileType, description: 'Change PDF to other formats.', status: 'wip' },
    { id: 'sign', name: 'bolt sign', icon: PenTool, description: 'Apply digital signatures.', status: 'wip' },
    { id: 'extract', name: 'bolt extract', icon: Search, description: 'Pull text and images from PDF.', status: 'wip' },
    { id: 'metadata', name: 'bolt metadata', icon: Settings, description: 'Edit document properties.', status: 'wip' },
  ];

  const renderDashboard = () => (
    <div className="p-8 max-w-6xl mx-auto space-y-12">
      <header className="text-center space-y-4">
        <h1 className="text-6xl font-black tracking-tighter">
          PDF<BoltBrand text="bolt" /> Suite
        </h1>
        <p className="font-serif italic text-xl opacity-60">Build tools for every PDF workflow.</p>
      </header>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {tools.map((tool) => (
          <motion.div 
            key={tool.id}
            whileHover={{ y: -4 }}
            className={`group relative border border-[#141414] p-6 bg-white/50 space-y-4 transition-all cursor-pointer ${tool.status === 'live' ? 'hover:bg-[#141414] hover:text-[#E4E3E0] shadow-[6px_6px_0px_#141414]' : 'opacity-60 grayscale cursor-not-allowed'} ${tool.highlight ? 'ring-2 ring-[#FF3300]/20' : ''}`}
            onClick={() => tool.status === 'live' && goToView('replace', '/replace')}
          >
            <tool.icon size={32} strokeWidth={1.5} className={tool.highlight ? 'text-[#FF3300]' : ''} />
            <div>
              <h3 className="font-black text-lg flex items-center gap-2">
                <BoltBrand text={tool.name} />
                {tool.status === 'live' && <span className="text-[8px] bg-[#FF3300] text-white px-1 py-0.5 rounded uppercase font-mono">LIVE</span>}
              </h3>
              <p className="text-xs opacity-60 font-mono mt-1">{tool.description}</p>
            </div>
            {tool.status === 'wip' && (
              <div className="absolute top-2 right-2 flex items-center gap-1 font-mono text-[8px] opacity-40">
                <RotateCcw size={10} className="animate-spin" /> WIP
              </div>
            )}
          </motion.div>
        ))}
      </div>
    </div>
  );

  const renderWIP = () => (
    <div className="flex-1 flex flex-col items-center justify-center p-20 text-center space-y-6">
      <div className="w-24 h-24 border border-[#141414] flex items-center justify-center bg-white shadow-[8px_8px_0px_#141414]">
        <RotateCcw size={48} className="animate-spin opacity-20" />
      </div>
      <h2 className="text-4xl font-black tracking-tighter uppercase italic">Work In Progress</h2>
      <p className="max-w-md font-mono text-sm opacity-50">
        Our engineers are currently forging this tool in the fires of Mount Determinism. 
        It will be ready for the <BoltBrand text="bolt" /> suite very soon.
      </p>
      <button 
        onClick={() => goToView('dashboard', '/')}
        className="px-6 py-2 border border-[#141414] font-mono text-xs hover:bg-[#141414] hover:text-[#E4E3E0] transition-all"
      >
        RETURN_TO_DASHBOARD
      </button>
    </div>
  );

  const renderAbout = () => (
    <div className="p-12 max-w-4xl mx-auto space-y-12">
      <div className="space-y-6">
        <h2 className="text-5xl font-black tracking-tighter italic text-[#141414]">The PDF<BoltBrand text="bolt" /> Suite</h2>
        <div className="prose prose-sm text-[#141414] font-mono leading-relaxed space-y-4 text-sm">
          <p>
            PDF<span className="text-[#FF3300]">bolt</span> is a premier ecosystem of tools designed for users who require precision, privacy, and performance in their PDF document lifecycles.
          </p>
          <p>
            Instead of providing a single monolithic application, we've broken down the PDF workflow into specialized, hyper-focused utilities. The suite is built under the "<span className="text-[#FF3300]">bolt</span>" brand architecture, offering tools like <span className="font-black text-[#FF3300]">bolt replace</span>, <span className="font-black text-[#FF3300]">bolt merge</span>, and <span className="font-black text-[#FF3300]">bolt split</span>.
          </p>
          <p>
            Our core technical differentiator is our <strong>Deterministic Engine</strong>. Unlike tools that paint over text or rely on fragile overlays, PDF<span className="text-[#FF3300]">bolt</span> rewrites PDF content streams so replacements stay in the real document structure. Our flagship tool, <span className="font-black text-[#FF3300]">bolt replace</span>, edits text drawing operands directly in the content stream.
          </p>
          <p>
            We believe that PDF tools should be as fast as they are capable. In this hosted web app, your files are processed by your own PDFBolt server instance—not shared with unrelated third parties—so you stay in control of deployment and data handling.
          </p>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-8 py-8 border-y border-[#141414]/10">
        <div>
          <h3 className="font-bold flex items-center gap-2 uppercase tracking-tighter text-[10px]"><ShieldCheck size={18} className="text-[#FF3300]" /> Full Privacy</h3>
          <p className="text-xs opacity-60 mt-2">Run PDFBolt on infrastructure you control. Replacement runs on the server you deploy, not in a shared multi-tenant SaaS pool.</p>
        </div>
        <div>
          <h3 className="font-bold flex items-center gap-2 uppercase tracking-tighter text-[10px]"><Zap size={18} className="text-[#FF3300]" /> High Fidelity</h3>
          <p className="text-xs opacity-60 mt-2">Maintain structural integrity. We don't flatten images or break links; we edit the raw content stream.</p>
        </div>
      </div>
      <button 
        onClick={() => goToView('dashboard', '/')}
        className="flex items-center gap-2 font-mono text-[10px] uppercase font-bold tracking-widest border-b border-[#141414] py-1 transition-all"
      >
        <ChevronRight size={14} /> Back to Suite
      </button>
    </div>
  );

  const renderContact = () => (
    <div className="p-12 max-w-4xl mx-auto space-y-12">
      <div className="space-y-6">
        <h2 className="text-5xl font-black tracking-tighter italic">Contact Us</h2>
        <p className="font-mono text-sm opacity-60 leading-relaxed uppercase tracking-tighter">For inquiries, feature requests, or support, send us a message.</p>
      </div>
      
      <form onSubmit={handleContactSubmit} className="grid grid-cols-1 md:grid-cols-1 gap-12">
        <div className="space-y-6 max-w-2xl">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="space-y-1">
              <label className="text-[10px] font-mono opacity-50 uppercase tracking-widest pl-1">Your Name</label>
              <input value={contactName} onChange={(e) => setContactName(e.target.value)} type="text" placeholder="NAME" className="w-full bg-white border border-[#141414] p-3 font-mono text-xs outline-none focus:ring-1 ring-[#FF3300]/20" />
            </div>
            <div className="space-y-1">
              <label className="text-[10px] font-mono opacity-50 uppercase tracking-widest pl-1">Your Email</label>
              <input value={contactEmail} onChange={(e) => setContactEmail(e.target.value)} type="email" placeholder="EMAIL" className="w-full bg-white border border-[#141414] p-3 font-mono text-xs outline-none focus:ring-1 ring-[#FF3300]/20" />
            </div>
          </div>
          <div className="space-y-1">
            <label className="text-[10px] font-mono opacity-50 uppercase tracking-widest pl-1">Subject</label>
            <input value={contactSubject} onChange={(e) => setContactSubject(e.target.value)} type="text" placeholder="SUBJECT" className="w-full bg-white border border-[#141414] p-3 font-mono text-xs outline-none focus:ring-1 ring-[#FF3300]/20" />
          </div>
          <div className="space-y-1">
            <label className="text-[10px] font-mono opacity-50 uppercase tracking-widest pl-1">Message</label>
            <textarea value={contactMessage} onChange={(e) => setContactMessage(e.target.value)} rows={6} placeholder="MESSAGE DATA..." className="w-full bg-white border border-[#141414] p-3 font-mono text-xs outline-none focus:ring-1 ring-[#FF3300]/20 resize-none" />
          </div>
          {contactStatus && (
            <p className={`text-xs font-mono ${contactStatus.type === 'ok' ? 'text-green-700' : 'text-red-600'}`} role="status">
              {contactStatus.msg}
            </p>
          )}
          <button type="submit" disabled={contactSending} className="bg-[#141414] text-[#E4E3E0] px-12 py-4 font-black tracking-widest uppercase hover:bg-[#FF3300] transition-colors shadow-[6px_6px_0px_#FF3300]/20 active:translate-x-[2px] active:translate-y-[2px] active:shadow-none disabled:opacity-50">
            {contactSending ? 'Sending...' : 'Send Inquiry'}
          </button>
        </div>
      </form>
    </div>
  );

  const renderReplace = () => (
    <div className="max-w-4xl mx-auto p-8 space-y-10">
      <header className="space-y-2">
        <h2 className="text-4xl font-black tracking-tighter">
          <BoltBrand text="bolt replace" />
        </h2>
        <p className="text-sm opacity-60 font-mono">
          Replace text inside PDF content streams and download the edited file.
        </p>
      </header>

      <div className="space-y-8">
        {/* File Selection */}
        <div className="space-y-2">
          <label className="text-[10px] font-mono uppercase tracking-widest opacity-50">PDF files</label>
          <div className="flex flex-wrap items-center gap-4">
            <button 
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="bg-white border border-[#141414] px-4 py-2 text-xs font-bold hover:bg-[#141414] hover:text-[#E4E3E0] transition-colors"
            >
              Choose files
            </button>
            <span className="text-xs font-mono opacity-50">
              {files.length === 0
                ? 'No file chosen'
                : files.length === 1
                  ? files[0].name
                  : `${files.length} PDFs selected`}
            </span>
            <input type="file" ref={fileInputRef} onChange={handleFileUpload} accept="application/pdf,.pdf" multiple className="hidden" />
          </div>
        </div>

        {/* Rules Table */}
        <div className="space-y-4">
          <div className="grid grid-cols-12 gap-4 text-[10px] font-mono uppercase opacity-40 px-2">
            <div className="col-span-5">Find</div>
            <div className="col-span-5">Replace with</div>
            <div className="col-span-2">Action</div>
          </div>
          
          <AnimatePresence initial={false}>
            {pairs.map((pair, idx) => (
              <motion.div 
                key={idx} 
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, scale: 0.95 }}
                className="grid grid-cols-12 gap-4 items-start"
              >
                <div className="col-span-5">
                  <input 
                    type="text" 
                    value={pair.find}
                    onChange={(e) => { const n = [...pairs]; n[idx].find = e.target.value; setPairs(n); }}
                    className="w-full bg-white border border-[#141414] p-3 font-mono text-sm outline-none focus:ring-1 ring-[#FF3300]/20"
                    placeholder="Search pattern..."
                  />
                </div>
                <div className="col-span-5">
                  <input 
                    type="text" 
                    value={pair.replace}
                    onChange={(e) => { const n = [...pairs]; n[idx].replace = e.target.value; setPairs(n); }}
                    className="w-full bg-white border border-[#141414] p-3 font-mono text-sm outline-none focus:ring-1 ring-[#FF3300]/20"
                    placeholder="New text..."
                  />
                </div>
                <div className="col-span-2">
                  <button 
                    type="button"
                    onClick={() => {
                      if (pairs.length <= 1) {
                        addLog('At least one find/replace rule is required.', 'error');
                        return;
                      }
                      setPairs(pairs.filter((_, i) => i !== idx));
                    }}
                    className="w-full h-[46px] border border-red-200 text-red-500 hover:bg-red-500 hover:text-white transition-colors flex items-center justify-center gap-2 text-xs font-bold uppercase tracking-tighter"
                  >
                    <Trash2 size={14} /> Delete
                  </button>
                </div>
              </motion.div>
            ))}
          </AnimatePresence>

          <button 
            type="button"
            onClick={() => setPairs([...pairs, { find: '', replace: '' }])}
            className="flex items-center gap-2 text-[10px] font-mono uppercase font-bold text-[#FF3300] hover:opacity-70 transition-opacity p-2"
          >
            <Plus size={14} /> Add find/replace rule
          </button>
        </div>

        {/* Settings Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-8 border-t border-[#141414]/10 pt-8">
          <div className="space-y-6">
            <div className="space-y-2">
              <label className="text-[10px] font-mono uppercase opacity-50 block">Match mode</label>
              <select
                value={matchMode}
                onChange={(e) => setMatchMode(e.target.value as MatchMode)}
                className="w-full bg-white border border-[#141414] p-3 font-mono text-xs outline-none"
              >
                <option value="exact">Exact</option>
                <option value="caseInsensitive">Case-insensitive</option>
                <option value="wholeWord">Whole-word</option>
                <option value="caseInsensitiveWholeWord">Case-insensitive + whole-word</option>
              </select>
            </div>

            <div className="space-y-2">
              <label className="text-[10px] font-mono uppercase opacity-50 block">Replace scope</label>
              <div className="flex gap-4">
                <select 
                  value={replaceScope}
                  onChange={(e) => setReplaceScope(e.target.value as ApiReplaceScope)}
                  className="flex-1 bg-white border border-[#141414] p-3 font-mono text-xs outline-none"
                >
                  <option value="all">All matches</option>
                  <option value="first">First match only</option>
                  <option value="nth">Specific occurrence</option>
                </select>
                {replaceScope === 'nth' && (
                  <input 
                    type="number" 
                    value={occurrenceIndex}
                    onChange={(e) => setOccurrenceIndex(Math.max(1, parseInt(e.target.value, 10) || 1))}
                    className="w-20 bg-white border border-[#141414] p-3 font-mono text-xs"
                    min={1}
                  />
                )}
              </div>
              <p className="text-[9px] opacity-40 font-mono italic">Occurrence index (1-based)</p>
            </div>

            <div className="space-y-2">
              <label className="text-[10px] font-mono uppercase opacity-50 block">Fallback font (.ttf/.otf)</label>
              <div className="flex flex-wrap items-center gap-4">
                <button type="button" onClick={() => fontInputRef.current?.click()} className="bg-white border border-[#141414] px-3 py-1.5 text-[10px] font-bold uppercase transition-colors hover:bg-gray-50">
                  Choose File
                </button>
                <span className="text-[10px] font-mono opacity-40">{fontFile ? fontFile.name : 'No file chosen'}</span>
                <input type="file" ref={fontInputRef} onChange={handleFontUpload} accept=".ttf,.otf,font/ttf,font/otf" className="hidden" />
              </div>
            </div>
          </div>

          <div className="space-y-4 pt-6">
             <div className="flex items-center gap-3">
               <button 
                 type="button"
                 onClick={() => setStrict(!strict)}
                 className={`w-5 h-5 border border-[#141414] flex items-center justify-center transition-colors ${strict ? 'bg-[#FF3300] text-white border-[#FF3300]' : 'bg-white'}`}
               >
                 {strict && <CheckCircle2 size={12} />}
               </button>
               <span className="text-xs font-bold uppercase tracking-tighter">Strict same-length mode</span>
             </div>

             <div className="flex items-center gap-3">
               <button 
                 type="button"
                 onClick={() => setPreserveStyle(!preserveStyle)}
                 className={`w-5 h-5 border border-[#141414] flex items-center justify-center transition-colors ${preserveStyle ? 'bg-[#141414] text-white' : 'bg-white'}`}
               >
                 {preserveStyle && <CheckCircle2 size={12} />}
               </button>
               <span className="text-xs font-bold uppercase tracking-tighter">Preserve original style (bold/italic/font)</span>
             </div>
             
             <div className="mt-8 p-4 bg-[#FF3300]/5 border border-[#FF3300]/10 rounded-sm">
                <p className="text-[10px] font-mono leading-tight opacity-70">
                  <span className="font-bold text-[#FF3300]">ENGINE_NOTE:</span> Deterministic replacement ensures no layout shifts. If "Strict mode" is enabled, ensure finding and replacement strings match in character count.
                </p>
             </div>
          </div>
        </div>

        <button 
          type="button"
          disabled={files.length === 0 || isProcessing}
          onClick={handleRunReplacement}
          className={`w-full py-8 font-black text-2xl uppercase tracking-tighter transition-all shadow-[8px_8px_0px_#141414] active:translate-x-[2px] active:translate-y-[2px] active:shadow-none border-2 border-[#141414] ${files.length === 0 || isProcessing ? 'bg-gray-200 text-gray-400 cursor-not-allowed opacity-50' : 'bg-[#FF3300] text-white hover:bg-[#141414] hover:shadow-[8px_8px_0px_#FF3300]'}`}
        >
          {isProcessing ? 'Processing...' : 'Replace and Download'}
        </button>
      </div>

      {log.length > 0 && (
        <div className="bg-[#141414] p-4 font-mono text-[9px] text-[#E4E3E0]/70 space-y-1 max-h-32 overflow-y-auto">
          {log.map((l, i) => <div key={i}>{l.msg}</div>)}
        </div>
      )}
    </div>
  );

  return (
    <div className="min-h-screen bg-[#E4E3E0] text-[#141414] font-sans selection:bg-[#141414] selection:text-[#E4E3E0] flex flex-col">
      <div className="fixed inset-0 pointer-events-none opacity-[0.03] z-0" 
           style={{ backgroundImage: 'linear-gradient(#000 1px, transparent 1px), linear-gradient(90deg, #000 1px, transparent 1px)', backgroundSize: '24px 24px' }} 
      />

      <nav className="border-b border-[#141414] bg-white/80 backdrop-blur-sm sticky top-0 z-[100] px-6 h-16 flex items-center justify-between">
        <div className="flex items-center gap-8">
          <div 
            onClick={() => goToView('dashboard', '/')}
            className="flex items-center gap-1 cursor-pointer hover:opacity-80 transition-opacity"
          >
            <h1 className="text-2xl font-black tracking-tighter uppercase">
              PDF<BoltBrand text="bolt" />
            </h1>
          </div>
          
          <div className="hidden md:flex gap-6">
            <button type="button" onClick={() => goToView('dashboard', '/')} className={`text-[10px] font-mono uppercase tracking-widest hover:text-[#FF3300] transition-colors ${currentView === 'dashboard' ? 'text-[#FF3300] font-bold underline decoration-2 underline-offset-4' : ''}`}>Suite</button>
            <button type="button" onClick={() => goToView('about', '/about')} className={`text-[10px] font-mono uppercase tracking-widest hover:text-[#FF3300] transition-colors ${currentView === 'about' ? 'text-[#FF3300] font-bold underline decoration-2 underline-offset-4' : ''}`}>About</button>
            <button type="button" onClick={() => goToView('contact', '/contact')} className={`text-[10px] font-mono uppercase tracking-widest hover:text-[#FF3300] transition-colors ${currentView === 'contact' ? 'text-[#FF3300] font-bold underline decoration-2 underline-offset-4' : ''}`}>Contact</button>
          </div>
        </div>
        
        <div className="flex items-center gap-2">
          <div className={`w-2 h-2 rounded-full ${files.length > 0 ? 'bg-green-500 animate-pulse' : 'bg-orange-500'}`} title={files.length > 0 ? 'PDFs loaded' : 'No PDFs loaded'} />
        </div>
      </nav>

      <main className="flex-1 relative z-10">
        <AnimatePresence mode="wait">
          {currentView === 'dashboard' && (
            <motion.div key="dashboard" initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -20 }}>
              {renderDashboard()}
            </motion.div>
          )}

          {currentView === 'replace' && (
            <motion.div key="replace" initial={{ opacity: 0, scale: 0.98 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 1.02 }}>
              {renderReplace()}
            </motion.div>
          )}

          {['merge', 'split', 'compress', 'convert', 'sign', 'extract', 'metadata'].includes(currentView) && (
            <motion.div key="wip" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              {renderWIP()}
            </motion.div>
          )}

          {currentView === 'about' && (
            <motion.div key="about" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              {renderAbout()}
            </motion.div>
          )}

          {currentView === 'contact' && (
            <motion.div key="contact" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              {renderContact()}
            </motion.div>
          )}
        </AnimatePresence>
      </main>

      <footer className="h-10 border-t border-[#141414] bg-[#141414] text-[9px] text-[#E4E3E0]/50 font-mono px-6 flex items-center justify-between uppercase tracking-widest">
        <div className="flex gap-6">
          <span>Engine: <BoltBrand text="bolt-v1" /></span>
        </div>
        <div>PDF<BoltBrand text="bolt" /> © 2026</div>
      </footer>

      <style dangerouslySetInnerHTML={{ __html: `
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;700;900&family=JetBrains+Mono:wght@400;700&family=Playfair+Display:ital@1&display=swap');
        .font-serif { font-family: 'Playfair Display', serif; }
      `}} />
    </div>
  );
}
