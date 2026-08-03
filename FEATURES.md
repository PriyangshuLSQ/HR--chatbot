# HRSmart Employee Query Bot

## Overview
A beautiful, modern AI-powered HR chatbot that provides 24/7 employee support with intelligent FAQ matching and HR admin management.

## Key Features

### Employee Chat Interface
- **No Login Required**: Direct access to chat without authentication
- **Instant Message Support**: Ask questions about leave, payroll, benefits, and compliance
- **Quick Topic Categories**: Browse common HR topics with icon-based buttons
  - Leave Management
  - Payroll
  - Benefits
  - Compliance
- **AI-Powered Responses**: Smart keyword matching with 10+ pre-loaded FAQs
- **Smooth Animations**: Beautiful message animations and loading states
- **Professional Design**: Modern, clean interface with premium styling
- **Premium Icons**: All SVG icons (no emojis) for a professional look
- **Responsive Layout**: Optimized for desktop and mobile

### HR Admin Dashboard
- **FAQ Management**: Add, edit, and manage FAQs with categories
- **Escalation Tracking**: Monitor pending and in-progress escalations
- **File Upload Support**: Upload CSV, Excel, and JSON knowledge bases
- **Admin-Only Access**: Secure login for HR administrators

## Design Highlights

### Color Palette
- Primary: #0969da (Modern Blue)
- Success: #1a7f37 (Green)
- Background: #fafbfc (Clean White)
- Foreground: #0d1117 (Deep Navy)
- Professional and accessible color scheme

### Typography
- System font stack for optimal readability
- Consistent font sizing and weight hierarchy
- Responsive text scaling for all devices

### Components
- Custom-built components (no external UI libraries)
- Smooth transitions and animations
- Professional card layouts
- Intuitive form inputs with focus states
- Icon-based navigation and actions

## Quick Start

### Employee
1. Visit `http://localhost:3000/chat`
2. Start asking HR questions immediately
3. Browse quick topic categories or type custom questions
4. Receive instant AI-powered responses

### HR Admin
1. Visit `http://localhost:3000/login`
2. Click "Try Demo Account" or login with:
   - Email: `hr@company.com`
   - Password: `demo`
3. Manage FAQs, track escalations, upload knowledge bases

## Architecture

- **Frontend**: Next.js 16 with React 19
- **Styling**: Custom CSS with Tailwind utilities
- **Data**: In-memory mock data (ready for database integration)
- **Authentication**: Session-based for HR admin only
- **Response Logic**: Keyword-based FAQ matching with confidence scoring

## Files Structure
```
/app
  ├── /chat          - Employee chat interface (public, no auth)
  ├── /admin         - HR admin dashboard (admin only)
  ├── /login         - Admin login page
  └── page.tsx       - Landing redirect to chat

/lib
  ├── chatbot-data.ts       - FAQs, categories, response logic
  └── chatbot-auth.tsx      - Authentication context

/app/globals.css    - Premium color scheme and utilities
```

## Features Coming Soon
- Real AI model integration (OpenAI, Anthropic)
- Database storage for FAQs and conversations
- Advanced analytics and reporting
- Multilingual support
- Email escalation notifications
- Rich media support
- Admin audit logs

---

Built with care. No libraries, just pure, beautiful design.
