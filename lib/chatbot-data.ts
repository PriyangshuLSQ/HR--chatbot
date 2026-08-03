export interface Message {
  id: string;
  type: 'user' | 'bot';
  content: string;
  timestamp: Date;
  escalated?: boolean;
}

export interface FAQ {
  id: string;
  question: string;
  answer: string;
  category: string;
  keywords: string[];
  createdAt: Date;
}

export interface Escalation {
  id: string;
  conversationId: string;
  userQuery: string;
  reason: string;
  status: 'pending' | 'resolved' | 'in-progress';
  createdAt: Date;
  assignedTo?: string;
  notes?: string;
}

export interface Conversation {
  id: string;
  userId: string;
  messages: Message[];
  escalations: Escalation[];
  createdAt: Date;
  updatedAt: Date;
}

// Default FAQs
export const DEFAULT_FAQS: FAQ[] = [
  {
    id: '1',
    question: 'How do I apply for leave?',
    answer: 'To apply for leave, log into the Darwinbox portal, go to the Leave section, select the leave type (Casual, Sick, Earned, etc.), choose your dates, and submit. Your manager will receive a notification for approval. Leave requests are typically processed within 24 hours.',
    category: 'Leave Management',
    keywords: ['leave', 'apply', 'vacation', 'absent', 'time off'],
    createdAt: new Date(),
  },
  {
    id: '2',
    question: 'What are my current leave balances?',
    answer: 'You can check your leave balances in the Darwinbox portal under "My Leave Balance". It shows your available casual leave, sick leave, earned leave, and any other leave types. The balances are updated daily and reflect any pending approval requests.',
    category: 'Leave Management',
    keywords: ['balance', 'leave', 'available', 'remaining', 'quota'],
    createdAt: new Date(),
  },
  {
    id: '3',
    question: 'When will I receive my salary?',
    answer: 'Salaries are typically processed on the 25th of every month and credited to your registered bank account by the 28th. If you don\'t receive your salary by the 1st of the following month, please contact the Payroll team immediately.',
    category: 'Payroll',
    keywords: ['salary', 'paycheck', 'payment', 'wage', 'compensation'],
    createdAt: new Date(),
  },
  {
    id: '4',
    question: 'How do I access my payslip?',
    answer: 'Your payslip is available in the Darwinbox portal under "Payroll" > "My Payslips". You can view, download, or print your monthly payslips. Payslips are typically available on the 25th of every month.',
    category: 'Payroll',
    keywords: ['payslip', 'salary slip', 'receipt', 'download', 'view'],
    createdAt: new Date(),
  },
  {
    id: '5',
    question: 'What health insurance benefits do I have?',
    answer: 'We provide comprehensive health insurance coverage for you and your family. The plan includes medical, dental, and vision coverage. You can view the full details of your coverage in the Darwinbox portal under "Benefits". For specific queries, contact the Benefits team.',
    category: 'Benefits',
    keywords: ['health', 'insurance', 'medical', 'coverage', 'benefits'],
    createdAt: new Date(),
  },
  {
    id: '6',
    question: 'Is there maternity/paternity leave available?',
    answer: 'Yes, we offer paid maternity leave of 6 months and paternity leave of 2 weeks. You must inform HR at least 2 months in advance. Contact the HR team for the complete policy details and to initiate the process.',
    category: 'Leave Management',
    keywords: ['maternity', 'paternity', 'baby', 'parent', 'newborn'],
    createdAt: new Date(),
  },
  {
    id: '7',
    question: 'What is the company holiday calendar?',
    answer: 'The company holiday calendar includes national holidays, regional holidays based on your office location, and company-specific holidays. You can view the complete 2024 holiday calendar in the Darwinbox portal or on the company intranet.',
    category: 'Leave Management',
    keywords: ['holiday', 'festival', 'calendar', 'day off', 'company holidays'],
    createdAt: new Date(),
  },
  {
    id: '8',
    question: 'How do I submit an expense report?',
    answer: 'Expense reports can be submitted through the Darwinbox portal under "Expenses". Attach receipts, provide category details, and enter the amount. Reports are processed within 5-7 days. Ensure all receipts are clear and properly categorized.',
    category: 'Compliance',
    keywords: ['expense', 'reimbursement', 'receipt', 'report', 'spending'],
    createdAt: new Date(),
  },
  {
    id: '9',
    question: 'What is the data privacy policy?',
    answer: 'We take data privacy seriously and comply with all applicable regulations including GDPR. Personal data is processed only for legitimate business purposes. For detailed information, refer to our Privacy Policy on the intranet or contact the Compliance team.',
    category: 'Compliance',
    keywords: ['privacy', 'data', 'gdpr', 'security', 'confidential'],
    createdAt: new Date(),
  },
  {
    id: '10',
    question: 'How do I reset my Darwinbox password?',
    answer: 'Click "Forgot Password" on the Darwinbox login page and enter your email. You\'ll receive a password reset link. If you don\'t receive the email within 5 minutes, check your spam folder or contact IT Support.',
    category: 'Compliance',
    keywords: ['password', 'reset', 'forgot', 'login', 'access'],
    createdAt: new Date(),
  },
];

// Simulate keyword matching for FAQ retrieval
export function findRelevantFAQs(query: string, faqs: FAQ[]): { faq: FAQ; score: number }[] {
  const queryWords = query.toLowerCase().split(/\s+/);
  
  const results = faqs.map(faq => {
    let score = 0;
    
    // Check keywords
    faq.keywords.forEach(keyword => {
      queryWords.forEach(word => {
        if (keyword.includes(word) || word.includes(keyword)) {
          score += 2;
        }
      });
    });
    
    // Check question words
    const questionWords = faq.question.toLowerCase().split(/\s+/);
    queryWords.forEach(word => {
      questionWords.forEach(qWord => {
        if (qWord.includes(word) || word.includes(qWord)) {
          score += 1;
        }
      });
    });
    
    return { faq, score };
  });
  
  return results
    .filter(result => result.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 3);
}

// Generate bot response based on FAQ match
export function generateBotResponse(
  userQuery: string,
  faqs: FAQ[],
  confidenceThreshold: number = 0.7
): { response: string; escalated: boolean; faqId?: string } {
  const matches = findRelevantFAQs(userQuery, faqs);
  
  if (matches.length === 0 || matches[0].score === 0) {
    return {
      response:
        "I couldn't find a direct answer to your query. Would you like me to escalate this to our HR team? They'll get back to you within 24 hours.",
      escalated: false,
    };
  }
  
  const topMatch = matches[0];
  // Simple confidence calculation: matches[0].score / (max possible score)
  const confidence = Math.min(topMatch.score / 10, 1);
  
  if (confidence >= confidenceThreshold) {
    return {
      response: topMatch.faq.answer,
      escalated: false,
      faqId: topMatch.faq.id,
    };
  }
  
  return {
    response: `Based on your query, I found a potentially relevant answer, but I'm not entirely confident. Here's what I found:\n\n${topMatch.faq.answer}\n\nIf this doesn't help, I can escalate your query to our HR team.`,
    escalated: false,
    faqId: topMatch.faq.id,
  };
}

export const CHAT_CATEGORIES = [
  { id: 'leave', label: 'Leave Management', icon: '📋' },
  { id: 'payroll', label: 'Payroll', icon: '💰' },
  { id: 'benefits', label: 'Benefits', icon: '🎁' },
  { id: 'compliance', label: 'Compliance', icon: '⚖️' },
];
