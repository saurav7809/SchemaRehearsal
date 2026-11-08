import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts'

export default function RiskChart({ chart, total }: {
  chart: { name: string; value: number; fill: string }[]
  total: number
}) {
  return <div className="chart-area">
    <ResponsiveContainer width="100%" height={190}>
      <PieChart>
        <Pie data={chart} dataKey="value" nameKey="name" innerRadius={51} outerRadius={73} paddingAngle={3} isAnimationActive={false}>
          {chart.map(c => <Cell key={c.name} fill={c.fill} />)}
        </Pie>
        <Tooltip />
      </PieChart>
    </ResponsiveContainer>
    <span className="chart-total"><strong>{total}</strong><small>RUNS</small></span>
  </div>
}
