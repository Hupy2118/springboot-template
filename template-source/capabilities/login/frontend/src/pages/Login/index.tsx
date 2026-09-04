import { useContext, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useRequest } from 'ahooks';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { mockLogin } from '@/apis/login';
import { USER_INFO_KEY } from '@/login/constants';
import { GlobalContext } from '@/providers';

export default function Login() {
  const navigate = useNavigate();
  const { setUserInfo } = useContext(GlobalContext);
  const [form] = Form.useForm<{ memberId: string; memberName: string }>();
  const { loading, error, runAsync } = useRequest(mockLogin, {
    manual: true,
  });

  useEffect(() => {
    if (sessionStorage.getItem(USER_INFO_KEY))
      navigate('/page', { replace: true });
  }, [navigate]);

  const submit = async () => {
    const { memberId, memberName } = await form.validateFields();
    try {
      const member = await runAsync({ memberId: memberId.trim(), memberName: memberName.trim() });
      const userInfo = { userId: member.memberId, userNo: member.memberId, userName: member.memberName };
      sessionStorage.setItem(USER_INFO_KEY, JSON.stringify(userInfo));
      setUserInfo(userInfo);
      navigate('/page', { replace: true });
    } catch {
      // useRequest exposes the error for the form to render.
    }
  };

  return (
    <div className='authorization-login-page'>
      <Card className='authorization-login-card'>
        <Typography.Title level={2}>登录</Typography.Title>
        <Typography.Paragraph type='secondary'>
          请输入用户 ID 进入应用。
        </Typography.Paragraph>
        {error ? (
          <Alert
            type='error'
            showIcon
            message='登录失败'
            description={
              (error as {
                response?: { data?: { message?: string } };
                message?: string;
              })?.response?.data?.message || (error as { message?: string })?.message || '用户不存在或服务暂不可用。'
            }
          />
        ) : null}
        <Form form={form} layout='vertical' onFinish={submit}>
          <Form.Item
            name='memberId'
            label='用户 ID'
            rules={[
              { required: true, whitespace: true, message: '请输入用户 ID' },
            ]}
          >
            <Input
              autoFocus
              autoComplete='username'
              placeholder='例如：10000001'
            />
          </Form.Item>
          <Form.Item
            name='memberName'
            label='用户名称'
            rules={[
              { required: true, whitespace: true, message: '请输入用户名称' },
            ]}
          >
            <Input autoComplete='name' placeholder='例如：测试用户' />
          </Form.Item>
          <Button type='primary' htmlType='submit' block loading={loading}>
            {loading ? '登录中…' : '登录'}
          </Button>
        </Form>
      </Card>
    </div>
  );
}
